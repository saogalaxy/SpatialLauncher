using System.Drawing;
using System.Drawing.Imaging;
using System.Reflection;
using System.Runtime.InteropServices;
using SharpGen.Runtime;
using Vortice.MediaFoundation;

namespace SpatialLauncher.Desktop.Core.Stream;

/// <summary>
/// Windows Media Foundation AV1 encoder (Vortice): BGRA → LOBF OBU access units
/// suitable for Android MediaCodec (video/av01).
/// </summary>
public sealed class Av1FrameEncoder : IDisposable
{
    /// <summary>MFVideoFormat_AV1 — FOURCC 'AV01'.</summary>
    private static readonly Guid Av1Subtype = new("31305641-0000-0010-8000-00AA00389B71");

    private readonly object _lock = new();
    private IMFTransform? _mft;
    private IMFMediaEventGenerator? _events;
    private bool _asyncMft;
    private int _width;
    private int _height;
    private int _bitrateKbps;
    private long _frameIndex;
    private byte[]? _configObus;
    private byte[]? _av1c;
    private bool _forceKey;
    private bool _mfStarted;
    private bool _disposed;
    private int _nullStreak;
    private const int MfEventNoWait = 1; // MF_EVENT_FLAG_NO_WAIT
    private const int MfEventWait = 0;

    public bool IsReady
    {
        get { lock (_lock) return _mft != null; }
    }

    /// <summary>AV1CodecConfigurationRecord (av1C) for Android MediaFormat csd-0.</summary>
    public byte[]? Av1C
    {
        get { lock (_lock) return _av1c == null ? null : (byte[])_av1c.Clone(); }
    }

    public void Ensure(int width, int height, int bitrateKbps)
    {
        width &= ~1;
        height &= ~1;
        if (width < 32 || height < 32)
            throw new ArgumentOutOfRangeException(nameof(width));
        bitrateKbps = Math.Clamp(bitrateKbps, 1000, 40000);
        lock (_lock)
        {
            if (_mft != null && _width == width && _height == height && _bitrateKbps == bitrateKbps)
                return;
            TearDownUnlocked();
            StartupMf();
            CreateEncoder(width, height, bitrateKbps * 1000);
            _width = width;
            _height = height;
            _bitrateKbps = bitrateKbps;
            _frameIndex = 0;
            _forceKey = true;
            _nullStreak = 0;
            CaptureSequenceHeaderUnlocked();
        }
    }

    public void RequestKeyFrame()
    {
        lock (_lock) _forceKey = true;
    }

    public byte[]? Encode(Bitmap bitmap, bool forceKeyFrame = false)
    {
        ArgumentNullException.ThrowIfNull(bitmap);
        lock (_lock)
        {
            if (_mft == null)
                return null;

            int bw = bitmap.Width & ~1;
            int bh = bitmap.Height & ~1;
            if (bw != _width || bh != _height)
            {
                int kbps = _bitrateKbps > 0 ? _bitrateKbps : 8000;
                TearDownUnlocked();
                StartupMf();
                CreateEncoder(bw, bh, kbps * 1000);
                _width = bw;
                _height = bh;
                _bitrateKbps = kbps;
                _frameIndex = 0;
                _forceKey = true;
            }

            if (forceKeyFrame || _forceKey)
            {
                _forceKey = true;
            }

            byte[] nv12 = BgraToNv12(bitmap, _width, _height);
            long duration = 10_000_000L / Math.Max(1, FramePacing.TargetFps);
            long time = _frameIndex * duration;
            _frameIndex++;

            using var inBuf = MediaFactory.MFCreateMemoryBuffer(nv12.Length);
            inBuf.Lock(out IntPtr dst, out _, out _);
            Marshal.Copy(nv12, 0, dst, nv12.Length);
            inBuf.Unlock();
            inBuf.CurrentLength = nv12.Length;

            using var input = MediaFactory.MFCreateSample();
            input.AddBuffer(inBuf);
            input.SampleTime = time;
            input.SampleDuration = duration;

            if (_asyncMft)
                return FinalizeAu(EncodeAsyncUnlocked(input));

            try
            {
                _mft.ProcessInput(0, input, 0);
            }
            catch (SharpGenException ex) when ((uint)ex.HResult == 0xC00D36B5) // MF_E_NOTACCEPTING
            {
                var drained = DrainOutputsUnlocked(allowPrepend: true);
                try { _mft.ProcessInput(0, input, 0); }
                catch { return FinalizeAu(drained); }
            }
            catch
            {
                return null;
            }

            var au = DrainOutputsUnlocked(allowPrepend: true);
            if (au == null || au.Length == 0)
                au = DrainOutputsUnlocked(allowPrepend: true);
            return FinalizeAu(au);
        }
    }

    /// <summary>
    /// Hardware AV1 MFTs are async. Unlock + ProcessInput alone never yields
    /// samples — we must wait for METransformNeedInput / METransformHaveOutput.
    /// </summary>
    private byte[]? EncodeAsyncUnlocked(IMFSample input)
    {
        if (_mft == null || _events == null) return null;

        // Wait until the encoder wants input (or we already drained a pending output).
        if (!WaitForAsyncEvent(MediaEventTypes.TransformNeedInput, 200)
            && !PeekHaveOutput())
        {
            // First frames often need a kick: try ProcessInput anyway.
        }

        try
        {
            _mft.ProcessInput(0, input, 0);
        }
        catch (SharpGenException ex) when ((uint)ex.HResult == 0xC00D36B5)
        {
            var early = DrainOutputsUnlocked(allowPrepend: true);
            if (early != null) return early;
            try { _mft.ProcessInput(0, input, 0); }
            catch { return null; }
        }
        catch
        {
            return null;
        }

        if (!WaitForAsyncEvent(MediaEventTypes.TransformHaveOutput, 400))
        {
            // Drain any queued events then try ProcessOutput once.
            DrainAsyncEvents(40);
        }
        return DrainOutputsUnlocked(allowPrepend: true);
    }

    private bool PeekHaveOutput()
    {
        DrainAsyncEvents(0);
        return false;
    }

    public string? LastDebug { get; private set; }

    private bool WaitForAsyncEvent(MediaEventTypes want, int timeoutMs)
    {
        if (_events == null) return false;
        var sw = System.Diagnostics.Stopwatch.StartNew();
        int seen = 0;
        while (sw.ElapsedMilliseconds < timeoutMs)
        {
            IMFMediaEvent? ev = null;
            try
            {
                // Blocking wait — async MFTs queue NeedInput/HaveOutput here.
                ev = _events.GetEvent(MfEventWait);
            }
            catch (Exception ex)
            {
                LastDebug = $"GetEvent({want}): {ex.GetType().Name} {ex.Message}";
                System.Threading.Thread.Sleep(2);
                continue;
            }
            if (ev == null)
            {
                System.Threading.Thread.Sleep(2);
                continue;
            }
            using (ev)
            {
                var t = (MediaEventTypes)ev.EventType;
                seen++;
                LastDebug = $"event {t} (want {want}, seen={seen})";
                if (t == MediaEventTypes.Error)
                    return false;
                if (t == want)
                    return true;
            }
        }
        LastDebug = $"timeout waiting {want} after {timeoutMs}ms seen~{seen}";
        return false;
    }

    private void DrainAsyncEvents(int timeoutMs)
    {
        if (_events == null) return;
        var sw = System.Diagnostics.Stopwatch.StartNew();
        do
        {
            try
            {
                using var ev = _events.GetEvent(MfEventNoWait);
                if (ev == null) break;
            }
            catch { break; }
        } while (timeoutMs > 0 && sw.ElapsedMilliseconds < timeoutMs);
    }

    private byte[]? FinalizeAu(byte[]? au)
    {
        if (au == null || au.Length == 0)
        {
            _nullStreak++;
            // After a long dry spell, recreate the MFT on next Ensure size change;
            // RequestKeyFrame so the next real frame tries harder.
            if (_nullStreak >= 8)
                _forceKey = true;
            return null;
        }
        _nullStreak = 0;
        if (ContainsSequenceHeader(au))
        {
            TryUpdateConfigFromAu(au);
            _forceKey = false;
        }
        return au;
    }

    private void TryUpdateConfigFromAu(byte[] au)
    {
        // Only capture once — appending every keyframe bloated av1c to 15KB+ and
        // broke Quest MediaCodec csd-0 configure.
        if (_av1c is { Length: > 32 } && _configObus is { Length: > 8 })
            return;
        try
        {
            using var ms = new MemoryStream();
            int i = 0;
            while (i < au.Length)
            {
                int start = i;
                int b = au[i] & 0xFF;
                if ((b & 0x80) != 0) break;
                int type = (b >> 3) & 0x0F;
                bool hasSize = (b & 0x02) != 0;
                bool extension = (b & 0x04) != 0;
                i++;
                if (extension) { if (i >= au.Length) break; i++; }
                long obuSize = au.Length - i;
                if (hasSize)
                {
                    obuSize = 0;
                    for (int shift = 0; i < au.Length; shift += 7)
                    {
                        int nb = au[i++] & 0xFF;
                        obuSize |= (long)(nb & 0x7F) << shift;
                        if ((nb & 0x80) == 0) break;
                    }
                }
                int end = hasSize ? i + (int)obuSize : au.Length;
                if (end > au.Length) break;
                if (type == 1 || type == 5) // SEQUENCE_HEADER or METADATA
                    ms.Write(au, start, end - start);
                if (type == 6 || type == 3) break; // stop at first frame
                i = end;
                if (!hasSize) break;
            }
            // Real sequence headers are small; reject runaway blobs.
            if (ms.Length > 8 && ms.Length < 2048)
            {
                _configObus = ms.ToArray();
                _av1c = BuildAv1CFromConfigObus(_configObus);
            }
        }
        catch { /* keep prior */ }
    }

    // Warm-up removed: dummy frames caused some HW AV1 MFTs to stall and never
    // emit real SBS samples afterward.

    private byte[]? DrainOutputsUnlocked(bool allowPrepend)
    {
        if (_mft == null) return null;
        using var ms = new MemoryStream(128 * 1024);
        bool prepend = allowPrepend && _forceKey && _configObus is { Length: > 0 };
        if (prepend)
            ms.Write(_configObus!, 0, _configObus!.Length);

        bool got = false;
        for (int guard = 0; guard < 8; guard++)
        {
            var info = _mft.GetOutputStreamInfo(0);
            bool mftProvides = (info.Flags & (int)OutputStreamInfoFlags.OutputStreamProvidesSamples) != 0;
            IMFSample? outSample = null;
            IMFMediaBuffer? outBuf = null;
            if (!mftProvides)
            {
                outSample = MediaFactory.MFCreateSample();
                outBuf = MediaFactory.MFCreateMemoryBuffer(Math.Max(info.Size, 8192));
                outSample.AddBuffer(outBuf);
            }
            var odb = new OutputDataBuffer { StreamID = 0, Sample = outSample };
            Result hr;
            try
            {
                hr = _mft.ProcessOutput(ProcessOutputFlags.None, 1, ref odb, out _);
            }
            catch (SharpGenException ex)
            {
                hr = ex.HResult;
            }

            if (hr.Failure)
            {
                outBuf?.Dispose();
                outSample?.Dispose();
                if ((uint)hr.Code == 0xC00D6D60) // STREAM_CHANGE
                {
                    CaptureSequenceHeaderUnlocked();
                    if (allowPrepend && _configObus is { Length: > 0 } && ms.Length == 0)
                    {
                        ms.Write(_configObus, 0, _configObus.Length);
                        prepend = true;
                    }
                    continue;
                }
                LastDebug = $"ProcessOutput fail 0x{(uint)hr.Code:X8} provides={mftProvides} size={info.Size}";
                break;
            }

            try
            {
                var sample = odb.Sample ?? outSample;
                if (sample == null) continue;
                int bufCount = 1;
                try { bufCount = Math.Max(1, sample.BufferCount); } catch { /* 1 */ }
                for (int bi = 0; bi < bufCount; bi++)
                {
                    using var buf = sample.GetBufferByIndex(bi);
                    buf.Lock(out IntPtr ptr, out _, out int len);
                    try
                    {
                        if (len > 0)
                        {
                            var raw = new byte[len];
                            Marshal.Copy(ptr, raw, 0, len);
                            var lobf = MaybeUnlengthPrefix(raw);
                            ms.Write(lobf, 0, lobf.Length);
                            got = true;
                            LastDebug = $"ProcessOutput ok len={len} provides={mftProvides}";
                        }
                    }
                    finally
                    {
                        buf.Unlock();
                    }
                }
                if (mftProvides)
                    sample.Dispose();
            }
            finally
            {
                outBuf?.Dispose();
                outSample?.Dispose();
            }
        }

        if (!got && prepend)
            return null;
        return got ? ms.ToArray() : null;
    }

    private void CreateEncoder(int width, int height, int bitrate)
    {
        uint fps = (uint)Math.Clamp(FramePacing.TargetFps, 24, 60);
        var activates = EnumAv1Encoders();
        IMFTransform? transform = null;
        Exception? last = null;
        try
        {
            foreach (var act in activates)
            {
                IMFTransform? candidate = null;
                try
                {
                    candidate = act.ActivateObject<IMFTransform>();
                    bool isAsync = false;
                    try
                    {
                        isAsync = candidate.Attributes.GetUInt32(TransformAttributeKeys.TransformAsync) != 0;
                    }
                    catch { /* sync */ }

                    if (isAsync)
                    {
                        try
                        {
                            candidate.Attributes.Set(TransformAttributeKeys.TransformAsyncUnlock, 1u);
                        }
                        catch
                        {
                            candidate.Dispose();
                            candidate = null;
                            last = new InvalidOperationException("AV1 async MFT unlock failed.");
                            continue;
                        }
                    }

                    try
                    {
                        candidate.Attributes.Set(SinkWriterAttributeKeys.LowLatency, 1u);
                    }
                    catch { /* optional */ }

                    using var outType = MediaFactory.MFCreateMediaType();
                    outType.Set(MediaTypeAttributeKeys.MajorType, MediaTypeGuids.Video);
                    outType.Set(MediaTypeAttributeKeys.Subtype, Av1Subtype);
                    MediaFactory.MFSetAttributeSize(outType, MediaTypeAttributeKeys.FrameSize, (uint)width, (uint)height);
                    MediaFactory.MFSetAttributeRatio(outType, MediaTypeAttributeKeys.FrameRate, fps, 1u);
                    outType.Set(MediaTypeAttributeKeys.AvgBitrate, (uint)bitrate);
                    outType.Set(MediaTypeAttributeKeys.InterlaceMode, (uint)VideoInterlaceMode.Progressive);
                    outType.Set(MediaTypeAttributeKeys.AllSamplesIndependent, 1u);
                    candidate.SetOutputType(0, outType, 0);

                    using var inType = MediaFactory.MFCreateMediaType();
                    inType.Set(MediaTypeAttributeKeys.MajorType, MediaTypeGuids.Video);
                    inType.Set(MediaTypeAttributeKeys.Subtype, VideoFormatGuids.NV12);
                    MediaFactory.MFSetAttributeSize(inType, MediaTypeAttributeKeys.FrameSize, (uint)width, (uint)height);
                    MediaFactory.MFSetAttributeRatio(inType, MediaTypeAttributeKeys.FrameRate, fps, 1u);
                    inType.Set(MediaTypeAttributeKeys.InterlaceMode, (uint)VideoInterlaceMode.Progressive);
                    try { inType.Set(MediaTypeAttributeKeys.DefaultStride, width); } catch { /* optional */ }
                    candidate.SetInputType(0, inType, 0);

                    candidate.ProcessMessage(TMessageType.MessageNotifyBeginStreaming, UIntPtr.Zero);
                    candidate.ProcessMessage(TMessageType.MessageNotifyStartOfStream, UIntPtr.Zero);

                    try { act.DetachObject(); } catch { /* optional */ }

                    _asyncMft = isAsync;
                    _events = isAsync ? candidate.QueryInterfaceOrNull<IMFMediaEventGenerator>() : null;
                    if (isAsync && _events == null)
                    {
                        candidate.Dispose();
                        candidate = null;
                        last = new InvalidOperationException("AV1 async MFT has no event generator.");
                        continue;
                    }

                    transform = candidate;
                    candidate = null;
                    break;
                }
                catch (Exception ex)
                {
                    last = ex;
                    candidate?.Dispose();
                }
            }
        }
        finally
        {
            activates.Dispose();
        }

        if (transform == null)
            throw last ?? new InvalidOperationException("No usable AV1 encoder MFT found.");

        _mft = transform;
        CaptureSequenceHeaderUnlocked();
    }

    private static IMFActivateCollection EnumAv1Encoders()
    {
        var outFilter = new RegisterTypeInfo
        {
            GuidMajorType = MediaTypeGuids.Video,
            GuidSubtype = Av1Subtype
        };

        // Prefer sync local MFTs first (same order as H.264); HW-first stalled
        // on some GPUs with Ensure OK but zero ProcessOutput samples.
        uint[] flagSets =
        {
            (uint)(EnumFlag.EnumFlagSortandfilter | EnumFlag.EnumFlagSyncmft | EnumFlag.EnumFlagLocalmft),
            (uint)(EnumFlag.EnumFlagSortandfilter | EnumFlag.EnumFlagLocalmft | EnumFlag.EnumFlagHardware),
            (uint)(EnumFlag.EnumFlagSortandfilter | EnumFlag.EnumFlagAll)
        };

        foreach (uint flags in flagSets)
        {
            MediaFactory.MFTEnumEx(TransformCategoryGuids.VideoEncoder, flags, null, outFilter,
                out nint ppp, out uint count);
            if (count == 0)
                continue;
            return (IMFActivateCollection)Activator.CreateInstance(
                typeof(IMFActivateCollection),
                BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic,
                null,
                new object[] { ppp, count },
                null)!;
        }

        throw new InvalidOperationException("MFTEnumEx found no AV1 video encoders.");
    }

    private void CaptureSequenceHeaderUnlocked()
    {
        if (_mft == null) return;
        try
        {
            using var type = _mft.GetOutputCurrentType(0);
            byte[]? blob = null;
            try
            {
                blob = type.GetBlob(MediaTypeAttributeKeys.MpegSequenceHeader);
            }
            catch { /* ignore */ }

            if (blob == null || blob.Length == 0)
            {
                return; // keep prior config if any
            }

            if (LooksLikeAv1C(blob))
            {
                _av1c = blob;
                _configObus = ExtractConfigObusFromAv1C(blob);
            }
            else if (ContainsSequenceHeader(blob))
            {
                _configObus = NormalizeToLobf(blob);
                _av1c = BuildAv1CFromConfigObus(_configObus);
            }
            else
            {
                _configObus = NormalizeToLobf(blob);
                _av1c = BuildAv1CFromConfigObus(_configObus);
            }
        }
        catch
        {
            // keep prior
        }
    }

    private static bool LooksLikeAv1C(byte[] blob)
    {
        if (blob.Length < 4) return false;
        int marker = (blob[0] >> 7) & 1;
        int version = blob[0] & 0x7F;
        return marker == 1 && version == 1;
    }

    private static byte[] ExtractConfigObusFromAv1C(byte[] av1c)
    {
        if (av1c.Length <= 4) return Array.Empty<byte>();
        var obus = new byte[av1c.Length - 4];
        Buffer.BlockCopy(av1c, 4, obus, 0, obus.Length);
        return NormalizeToLobf(obus);
    }

    /// <summary>
    /// Minimal av1C (4-byte record + configOBUs) so Android can set csd-0.
    /// Profile/level bits are placeholders; config OBUs carry the real sequence header.
    /// </summary>
    private static byte[] BuildAv1CFromConfigObus(byte[] configObus)
    {
        var av1c = new byte[4 + configObus.Length];
        av1c[0] = 0x81; // marker=1, version=1
        av1c[1] = 0x0D; // seq_profile=0, seq_level_idx_0=13 (rough Main)
        av1c[2] = 0x00;
        av1c[3] = 0x00;
        Buffer.BlockCopy(configObus, 0, av1c, 4, configObus.Length);
        return av1c;
    }

    /// <summary>
    /// Flatten length-prefixed OBU packs and ensure obu_has_size_field=1 (Android LOBF).
    /// </summary>
    private static byte[] NormalizeToLobf(byte[] raw)
    {
        if (raw.Length == 0) return raw;
        byte[] flat = MaybeUnlengthPrefix(raw);
        using var ms = new MemoryStream(flat.Length + 16);
        int i = 0;
        while (i < flat.Length)
        {
            int b = flat[i] & 0xFF;
            if ((b & 0x80) != 0)
            {
                // Not an OBU — pass through remainder.
                ms.Write(flat, i, flat.Length - i);
                break;
            }
            int type = (b >> 3) & 0x0F;
            bool extension = (b & 0x04) != 0;
            bool hasSize = (b & 0x02) != 0;
            int hdrStart = i;
            i++;
            if (extension)
            {
                if (i >= flat.Length) break;
                i++;
            }
            int payloadStart = i;
            int payloadLen;
            if (hasSize)
            {
                long size = 0;
                for (int shift = 0; i < flat.Length; shift += 7)
                {
                    int nb = flat[i++] & 0xFF;
                    size |= (long)(nb & 0x7F) << shift;
                    if ((nb & 0x80) == 0) break;
                }
                payloadStart = i;
                payloadLen = (int)size;
                if (payloadLen < 0 || payloadStart + payloadLen > flat.Length)
                    break;
                // Already sized — copy as-is.
                ms.Write(flat, hdrStart, (payloadStart - hdrStart) + payloadLen);
                i = payloadStart + payloadLen;
            }
            else
            {
                // No size field: rest of buffer is this OBU (rare for multi-OBU).
                payloadLen = flat.Length - payloadStart;
                // Rebuild header with has_size=1.
                int newHdr = (b & ~0x02) | 0x02;
                ms.WriteByte((byte)newHdr);
                if (extension)
                    ms.WriteByte(flat[hdrStart + 1]);
                WriteLeb128(ms, payloadLen);
                ms.Write(flat, payloadStart, payloadLen);
                break;
            }
            if (type == 0) break;
        }
        return ms.Length > 0 ? ms.ToArray() : flat;
    }

    private static byte[] MaybeUnlengthPrefix(byte[] raw)
    {
        if (raw.Length < 8) return raw;
        int type = (raw[0] >> 3) & 0x0F;
        int forbidden = (raw[0] >> 7) & 1;
        if (forbidden == 0 && type >= 1 && type <= 15)
            return raw;

        using var ms = new MemoryStream(raw.Length);
        int i = 0;
        int packs = 0;
        while (i + 4 <= raw.Length)
        {
            int len = (raw[i] << 24) | (raw[i + 1] << 16) | (raw[i + 2] << 8) | raw[i + 3];
            i += 4;
            if (len <= 0 || len > raw.Length - i || len > 2 * 1024 * 1024)
                return raw;
            ms.Write(raw, i, len);
            i += len;
            packs++;
        }
        return packs > 0 && i == raw.Length ? ms.ToArray() : raw;
    }

    private static void WriteLeb128(System.IO.Stream s, int value)
    {
        uint v = (uint)Math.Max(0, value);
        while (true)
        {
            byte b = (byte)(v & 0x7F);
            v >>= 7;
            if (v != 0) b |= 0x80;
            s.WriteByte(b);
            if (v == 0) break;
        }
    }

    private static bool ContainsSequenceHeader(byte[] au)
    {
        int i = 0;
        while (i < au.Length)
        {
            int b = au[i] & 0xFF;
            if ((b & 0x80) != 0) return false;
            int type = (b >> 3) & 0x0F;
            bool hasSize = (b & 0x02) != 0;
            bool extension = (b & 0x04) != 0;
            i++;
            if (extension)
            {
                if (i >= au.Length) break;
                i++;
            }
            long obuSize = au.Length - i;
            if (hasSize)
            {
                obuSize = 0;
                for (int shift = 0; i < au.Length; shift += 7)
                {
                    int nb = au[i++] & 0xFF;
                    obuSize |= (long)(nb & 0x7F) << shift;
                    if ((nb & 0x80) == 0) break;
                }
            }
            if (type == 1) return true;
            if (obuSize < 0 || i + obuSize > au.Length) break;
            i += (int)obuSize;
            if (!hasSize) break;
        }
        return false;
    }

    private static byte[] BgraToNv12(Bitmap src, int width, int height)
    {
        var rect = new Rectangle(0, 0, width, height);
        Bitmap? framed = null;
        Bitmap bmp = src;
        if (src.Width != width || src.Height != height)
        {
            framed = new Bitmap(width, height, PixelFormat.Format32bppArgb);
            using var g = Graphics.FromImage(framed);
            g.DrawImage(src, rect);
            bmp = framed;
        }

        var data = bmp.LockBits(rect, ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
        try
        {
            int stride = data.Stride;
            var bgra = new byte[stride * height];
            Marshal.Copy(data.Scan0, bgra, 0, bgra.Length);
            int ySize = width * height;
            var nv12 = new byte[ySize + ySize / 2];
            int uvOff = ySize;
            for (int y = 0; y < height; y++)
            {
                int row = y * stride;
                int yRow = y * width;
                for (int x = 0; x < width; x++)
                {
                    int p = row + x * 4;
                    int b = bgra[p];
                    int g = bgra[p + 1];
                    int r = bgra[p + 2];
                    int Y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                    nv12[yRow + x] = (byte)Math.Clamp(Y, 0, 255);
                }
            }
            for (int y = 0; y < height; y += 2)
            {
                int row0 = y * stride;
                int row1 = Math.Min(y + 1, height - 1) * stride;
                for (int x = 0; x < width; x += 2)
                {
                    long r = 0, g = 0, b = 0;
                    for (int yy = 0; yy < 2; yy++)
                    {
                        int row = yy == 0 ? row0 : row1;
                        for (int xx = 0; xx < 2; xx++)
                        {
                            int p = row + (x + xx) * 4;
                            b += bgra[p];
                            g += bgra[p + 1];
                            r += bgra[p + 2];
                        }
                    }
                    r /= 4; g /= 4; b /= 4;
                    int U = ((-38 * (int)r - 74 * (int)g + 112 * (int)b + 128) >> 8) + 128;
                    int V = ((112 * (int)r - 94 * (int)g - 18 * (int)b + 128) >> 8) + 128;
                    int uvIndex = uvOff + (y / 2) * width + x;
                    nv12[uvIndex] = (byte)Math.Clamp(U, 0, 255);
                    nv12[uvIndex + 1] = (byte)Math.Clamp(V, 0, 255);
                }
            }
            return nv12;
        }
        finally
        {
            bmp.UnlockBits(data);
            framed?.Dispose();
        }
    }

    private void StartupMf()
    {
        if (_mfStarted) return;
        MediaFactory.MFStartup();
        _mfStarted = true;
    }

    private void TearDownUnlocked()
    {
        if (_mft != null)
        {
            try
            {
                _mft.ProcessMessage(TMessageType.MessageNotifyEndOfStream, UIntPtr.Zero);
                _mft.ProcessMessage(TMessageType.MessageCommandFlush, UIntPtr.Zero);
                _mft.ProcessMessage(TMessageType.MessageNotifyEndStreaming, UIntPtr.Zero);
            }
            catch { /* ignore */ }
            _mft.Dispose();
            _mft = null;
        }
        _events = null;
        _asyncMft = false;
        _configObus = null;
        _av1c = null;
        _forceKey = true;
        _nullStreak = 0;
    }

    public void Dispose()
    {
        if (_disposed) return;
        lock (_lock)
        {
            TearDownUnlocked();
            if (_mfStarted)
            {
                try { MediaFactory.MFShutdown(); } catch { /* ignore */ }
                _mfStarted = false;
            }
            _disposed = true;
        }
    }
}
