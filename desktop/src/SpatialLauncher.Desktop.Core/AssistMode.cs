namespace SpatialLauncher.Desktop.Core;

/// <summary>Reader pipeline modes — mirrors Quest AssistMode (no browser/books).</summary>
public enum AssistMode
{
    /// <summary>OCR → Piper (no OPUS).</summary>
    Read,
    /// <summary>OCR → OPUS → Piper when UseOpus is on.</summary>
    Translate,
    /// <summary>Audio → STT → OPUS → Piper when UseOpus is on.</summary>
    Listen,
    /// <summary>OCR → OPUS → Piper + caption when UseOpus is on.</summary>
    Share
}

public static class AssistModeExtensions
{
    public static string PipelineLabel(this AssistMode mode, bool useOpus) => mode switch
    {
        AssistMode.Translate => useOpus
            ? "Active: OCR → OPUS → Piper  (foreign on-screen → English)"
            : "Active: OCR → Piper  (Translate · OPUS off)",
        AssistMode.Listen => useOpus
            ? "Active: Audio → STT → OPUS → Piper"
            : "Active: Audio → STT → Piper  (Listen · OPUS off)",
        AssistMode.Share => useOpus
            ? "Active: OCR → OPUS → Piper + caption"
            : "Active: OCR → Piper + caption  (Share · OPUS off)",
        _ => "Active: OCR → Piper  (ML Kit / Windows OCR · no OPUS)"
    };
}
