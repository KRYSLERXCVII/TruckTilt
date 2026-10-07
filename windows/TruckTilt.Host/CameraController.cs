using System.Diagnostics;
using System.Runtime.InteropServices;

namespace TruckTilt.Host;

/// <summary>
/// Converts TruckTilt's relative camera-trackpad deltas into Windows relative
/// mouse movement. Movement is injected only while ETS2/ATS is the foreground
/// application, so the touchpad cannot move the desktop when the simulator is
/// not active.
/// </summary>
internal static class CameraController
{
    private const double Sensitivity = 1.35;
    private const uint InputMouse = 0;
    private const uint MouseMove = 0x0001;
    private const uint MouseLeftDown = 0x0002;
    private const uint MouseLeftUp = 0x0004;

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint type;
        public MOUSEINPUT mi;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);

    public static bool TryParse(string packet, out double dx, out double dy)
    {
        dx = dy = 0;
        try
        {
            var p = packet.Split('|');
            if (p.Length != 3 || !p[0].Equals("CAMERA", StringComparison.OrdinalIgnoreCase))
                return false;

            dx = double.Parse(p[1], System.Globalization.CultureInfo.InvariantCulture);
            dy = double.Parse(p[2], System.Globalization.CultureInfo.InvariantCulture);
            dx = Math.Clamp(dx, -1000, 1000);
            dy = Math.Clamp(dy, -1000, 1000);
            return true;
        }
        catch
        {
            return false;
        }
    }

    public static void MoveRelative(double dx, double dy)
    {
        if (!IsTruckSimulatorForeground())
            return;

        int x = (int)Math.Round(dx * Sensitivity);
        int y = (int)Math.Round(dy * Sensitivity);
        if (x == 0 && y == 0)
            return;

        var input = new INPUT
        {
            type = InputMouse,
            mi = new MOUSEINPUT
            {
                dx = x,
                dy = y,
                mouseData = 0,
                dwFlags = MouseMove,
                time = 0,
                dwExtraInfo = IntPtr.Zero
            }
        };

        _ = SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
    }

    public static void LeftClick()
    {
        if (!IsTruckSimulatorForeground())
            return;

        var down = new INPUT
        {
            type = InputMouse,
            mi = new MOUSEINPUT
            {
                dx = 0,
                dy = 0,
                mouseData = 0,
                dwFlags = MouseLeftDown,
                time = 0,
                dwExtraInfo = IntPtr.Zero
            }
        };

        var up = new INPUT
        {
            type = InputMouse,
            mi = new MOUSEINPUT
            {
                dx = 0,
                dy = 0,
                mouseData = 0,
                dwFlags = MouseLeftUp,
                time = 0,
                dwExtraInfo = IntPtr.Zero
            }
        };

        _ = SendInput(2, new[] { down, up }, Marshal.SizeOf<INPUT>());
    }

    private static bool IsTruckSimulatorForeground()
    {
        var hwnd = GetForegroundWindow();
        if (hwnd == IntPtr.Zero)
            return false;

        GetWindowThreadProcessId(hwnd, out uint pid);
        if (pid == 0)
            return false;

        try
        {
            var name = Process.GetProcessById((int)pid).ProcessName;
            return name.Equals("eurotrucks2", StringComparison.OrdinalIgnoreCase)
                || name.Equals("amtrucks", StringComparison.OrdinalIgnoreCase);
        }
        catch
        {
            return false;
        }
    }
}
