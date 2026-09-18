using System.Drawing;
using System.Drawing.Imaging;
using System.Reflection;
using System.Runtime.InteropServices;
using SharpGen.Runtime;
using Vortice.MediaFoundation;

namespace SpatialLauncher.Desktop.Core.Stream;

/// <summary>
/// Windows Media Foundation H.264 encoder (Vortice): BGRA → Annex-B access units.
/// </summary>
public sealed class H264FrameEncoder : IDisposable
{
    private readonly object _lock = new();
    private IMFTransform? _mft;
    private IMFMediaEventGenerator? _events;
    private bool _asyncMft;
    private int _width;
    private int _height;
    private int _bitrateKbps;
    private long _frameIndex;
    private byte[]? _sequenceHeaderAnnexB;
    private bool _forceHeader = true;
    private bool _mfStarted;
    private bool _disposed;
    private int _nullStreak;
    private const int MfEventNoWait = 1; // MF_EVENT_FLAG_NO_WAIT
    private const int MfEventWait = 0;

    public string? LastDebug { get; private set; }

    private static readonly byte[] StartCode = { 0, 0, 0, 1 };

    public bool IsReady
    {
        get { lock (_lock) return _mft != null; }
    }

    public void Ensure(int width, int height, int bitrateKbps)
    {
        width &= ~1;
        height &= ~1;
        if (width < 32 || height < 32)
            throw new ArgumentOutOfRangeException(nameof(width));
        bitrateKbps = Math.Clamp(bitrateKbps, 500, 40000);
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
            _forceHeader = true;
            _nullStreak = 0;
        }
    }

    public void RequestKeyFrame()
    {
        lock (_lock) _forceHeader = true;
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
                _forceHeader = true;
                _nullStreak = 0;
            }

            bool wantKey = forceKeyFrame || _forceHeader;
            if (wantKey)
                _forceHeader = true;

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
            if (wantKey)
            {
                try
                {
                    // Hint IDR — works on NVENC/QSV/AMF MFTs that honor CleanPoint.
                    input.Set(SampleAttributeKeys.CleanPoint, 1);
                }
                catch
                {
                    // optional
                }
            }

            if (_asyncMft)
                return EncodeAsyncUnlocked(input);

            try
            {
                _mft.ProcessInput(0, input, 0);
            }
            catch (SharpGenException ex) when ((uint)ex.HResult == 0xC00D36B5) // MF_E_NOTACCEPTING
            {
                var drained = DrainOutputsUnlocked();
                try { _mft.ProcessInput(0, input, 0); }
                catch { return drained; }
            }
            catch
            {
                return null;
            }

            return DrainOutputsUnlocked();
        }
    }

    /// <summary>
    /// Hardware H.264 MFTs are async. Unlock + ProcessInput alone never yields
    /// samples — we must wait for METransformNeedInput / METransformHaveOutput
    /// (same pattern as <see cref="Av1FrameEncoder"/>).
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
            var early = DrainOutputsUnlocked();
            if (early != null) return TrackStreak(early);
            try { _mft.ProcessInput(0, input, 0); }
            catch { return TrackStreak(null); }
        }
        catch
        {
            return TrackStreak(null);
        }

        if (!WaitForAsyncEvent(MediaEventTypes.TransformHaveOutput, 400))
        {
            // Drain any queued events then try ProcessOutput once.
            DrainAsyncEvents(40);
        }
        return TrackStreak(DrainOutputsUnlocked());
    }

    private byte[]? TrackStreak(byte[]? au)
    {
        if (au == null || au.Length == 0)
        {
            _nullStreak++;
            if (_nullStreak >= 8)
                _forceHeader = true;
            return null;
        }
        _nullStreak = 0;
        return au;
    }

    private bool PeekHaveOutput()
    {
        DrainAsyncEvents(0);
        return false;
    }

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

    private byte[]? DrainOutputsUnlocked()
    {
        if (_mft == null) return null;
        using var ms = new MemoryStream(64 * 1024);
        bool prepend = _forceHeader && _sequenceHeaderAnnexB is { Length: > 0 };
        if (prepend)
        {
            ms.Write(_sequenceHeaderAnnexB!, 0, _sequenceHeaderAnnexB!.Length);
            _forceHeader = false;
        }

        bool got = false;
        while (true)
        {
            var info = _mft.GetOutputStreamInfo(0);
            using var outSample = MediaFactory.MFCreateSample();
            using var outBuf = MediaFactory.MFCreateMemoryBuffer(Math.Max(info.Size, 4096));
            outSample.AddBuffer(outBuf);
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
                if ((uint)hr.Code == 0xC00D6D60) // STREAM_CHANGE
                {
                    CaptureSequenceHeaderUnlocked();
                    _forceHeader = true;
                    continue;
                }
                break; // NEED_MORE_INPUT or other
            }

            outBuf.Lock(out IntPtr ptr, out _, out int len);
            try
            {
                if (len > 0)
                {
                    var raw = new byte[len];
                    Marshal.Copy(ptr, raw, 0, len);
                    var annex = AvccOrAnnexToAnnexB(raw);
                    ms.Write(annex, 0, annex.Length);
                    got = true;
                }
            }
            finally
            {
                outBuf.Unlock();
            }
        }

        if (!got && prepend)
        {
            _forceHeader = true;
            return null;
        }
        return got ? ms.ToArray() : null;
    }

    private void CreateEncoder(int width, int height, int bitrate)
    {
        // Match present cadence (72) — capping at 60 made encode timing drift vs capture.
        uint fps = (uint)Math.Clamp(FramePacing.TargetFps, 24, 120);
        var activates = EnumH264Encoders();
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
                            last = new InvalidOperationException("H.264 async MFT unlock failed.");
                            continue;
                        }
                    }
                    try
                    {
                        // MF_LOW_LATENCY — ask the MFT to minimize buffering.
                        candidate.Attributes.Set(SinkWriterAttributeKeys.LowLatency, 1u);
                    }
                    catch
                    {
                        // optional
                    }

                    using var outType = MediaFactory.MFCreateMediaType();
                    outType.Set(MediaTypeAttributeKeys.MajorType, MediaTypeGuids.Video);
                    outType.Set(MediaTypeAttributeKeys.Subtype, VideoFormatGuids.H264);
                    MediaFactory.MFSetAttributeSize(outType, MediaTypeAttributeKeys.FrameSize, (uint)width, (uint)height);
                    MediaFactory.MFSetAttributeRatio(outType, MediaTypeAttributeKeys.FrameRate, fps, 1u);
                    outType.Set(MediaTypeAttributeKeys.AvgBitrate, (uint)bitrate);
                    outType.Set(MediaTypeAttributeKeys.InterlaceMode, (uint)VideoInterlaceMode.Progressive);
                    // Do NOT set AllSamplesIndependent — that forces every AU to a sync
                    // sample on many MFTs and collapses quality to mushy ~300-byte frames.
                    candidate.SetOutputType(0, outType, 0);

                    using var inType = MediaFactory.MFCreateMediaType();
                    inType.Set(MediaTypeAttributeKeys.MajorType, MediaTypeGuids.Video);
                    inType.Set(MediaTypeAttributeKeys.Subtype, VideoFormatGuids.NV12);
                    MediaFactory.MFSetAttributeSize(inType, MediaTypeAttributeKeys.FrameSize, (uint)width, (uint)height);
                    MediaFactory.MFSetAttributeRatio(inType, MediaTypeAttributeKeys.FrameRate, fps, 1u);
                    inType.Set(MediaTypeAttributeKeys.InterlaceMode, (uint)VideoInterlaceMode.Progressive);
                    candidate.SetInputType(0, inType, 0);

                    candidate.ProcessMessage(TMessageType.MessageNotifyBeginStreaming, UIntPtr.Zero);
                    candidate.ProcessMessage(TMessageType.MessageNotifyStartOfStream, UIntPtr.Zero);

                    // Detach so disposing the activate collection does not shut the MFT down.
                    try { act.DetachObject(); } catch { /* optional */ }

                    _asyncMft = isAsync;
                    _events = isAsync ? candidate.QueryInterfaceOrNull<IMFMediaEventGenerator>() : null;
                    if (isAsync && _events == null)
                    {
                        candidate.Dispose();
                        candidate = null;
                        last = new InvalidOperationException("H.264 async MFT has no event generator.");
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
            throw last ?? new InvalidOperationException("No usable H.264 encoder MFT found.");

        _mft = transform;
        CaptureSequenceHeaderUnlocked();
    }

    private static IMFActivateCollection EnumH264Encoders()
    {
        var outFilter = new RegisterTypeInfo
        {
            GuidMajorType = MediaTypeGuids.Video,
            GuidSubtype = VideoFormatGuids.H264
        };

        // Prefer sync local MFTs first (same order as AV1); HW-first stalled
        // on some GPUs with Ensure OK but zero ProcessOutput samples (Quest
        // MPEG pump got one AU then starved). Async HW still works via the
        // event pump when no sync encoder is available.
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

        throw new InvalidOperationException("MFTEnumEx found no H.264 video encoders.");
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
                // Prefer typed helper if present.
                blob = type.GetBlob(MediaTypeAttributeKeys.MpegSequenceHeader);
            }
            catch
            {
                // ignore
            }

            if (blob == null || blob.Length == 0)
            {
                _sequenceHeaderAnnexB = null;
                return;
            }
            _sequenceHeaderAnnexB = AvccOrAnnexToAnnexB(blob);
            _forceHeader = true;
        }
        catch
        {
            _sequenceHeaderAnnexB = null;
        }
    }

    private static byte[] AvccOrAnnexToAnnexB(byte[] data)
    {
        if (data.Length >= 4 && data[0] == 0 && data[1] == 0 && (data[2] == 1 || (data[2] == 0 && data[3] == 1)))
            return data;

        using var ms = new MemoryStream(data.Length + 32);
        int i = 0;
        while (i + 4 <= data.Length)
        {
            int naluLen = (data[i] << 24) | (data[i + 1] << 16) | (data[i + 2] << 8) | data[i + 3];
            i += 4;
            if (naluLen <= 0 || i + naluLen > data.Length)
            {
                ms.SetLength(0);
                ms.Write(StartCode, 0, 4);
                ms.Write(data, 0, data.Length);
                return ms.ToArray();
            }
            ms.Write(StartCode, 0, 4);
            ms.Write(data, i, naluLen);
            i += naluLen;
        }
        return ms.ToArray();
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
        _sequenceHeaderAnnexB = null;
        _forceHeader = true;
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
