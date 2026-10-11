using System.Text;

namespace TruckTilt.Host;

/// <summary>Separates concise public status from developer diagnostics.</summary>
internal static class HostLog
{
    private static readonly object Sync = new();
    private static string? _logPath;
    public static bool DebugEnabled { get; private set; }

    public static void Initialize(bool debugEnabled)
    {
        DebugEnabled = debugEnabled;
        try
        {
            string folder = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "TruckTilt", "logs");
            Directory.CreateDirectory(folder);
            _logPath = Path.Combine(folder, $"host-{DateTime.Now:yyyyMMdd}.log");
            WriteFile($"--- TruckTilt Host started {DateTime.Now:yyyy-MM-dd HH:mm:ss} (debug={DebugEnabled}) ---");
        }
        catch { _logPath = null; }
    }

    public static void Public(string message)
    {
        Console.WriteLine(message);
        WriteFile(message);
    }

    public static void Debug(string message)
    {
        WriteFile("[DEBUG] " + message);
        if (DebugEnabled) Console.WriteLine("[DEBUG] " + message);
    }

    public static void Error(string message, Exception? exception = null)
    {
        string detail = exception is null ? message : message + Environment.NewLine + exception;
        Console.ForegroundColor = ConsoleColor.Red;
        Console.WriteLine(message);
        Console.ResetColor();
        WriteFile("[ERROR] " + detail);
        if (DebugEnabled && exception is not null) Console.WriteLine(exception);
    }

    private static void WriteFile(string message)
    {
        try
        {
            if (_logPath is null) return;
            lock (Sync) File.AppendAllText(_logPath, $"[{DateTime.Now:HH:mm:ss.fff}] {message}{Environment.NewLine}", Encoding.UTF8);
        }
        catch { /* Logging must never interrupt controller operation. */ }
    }
}
