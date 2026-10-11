using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text;
using TruckTilt.Host;

const int Port = 45454;
const int TimeoutMs = 500;

Console.Title = "TruckTilt Host";
Console.WriteLine("TruckTilt Host v0.12.6 - ETS2 DirectInput Wheel");
Console.WriteLine("================================================");
Console.WriteLine($"Listening for TruckTilt phones on UDP {Port}");
Console.WriteLine("Device: TruckTilt Virtual Steering Wheel (DirectInput/HID)");
Console.WriteLine("Axes: Steering / Throttle / Brake / Clutch / Retarder");
Console.WriteLine("Buttons: 32 independent DirectInput wheel buttons");
Console.WriteLine("Interior CAM: separate virtual Xbox controller START button (configurable in-game)");
Console.WriteLine("Press Ctrl+C to exit.");
Console.WriteLine();

VirtualWheel? wheel = null;
VirtualController? interiorCameraController = null;
try
{
    Console.WriteLine("Initializing HIDMaestro...");
    wheel = new VirtualWheel();
    Console.WriteLine("VIRTUAL STEERING WHEEL READY");
    Console.WriteLine("ETS2 / ATS auto-binding will be applied when the game is detected.");
}
catch (Exception ex)
{
    Console.WriteLine();
    Console.WriteLine("VIRTUAL WHEEL ERROR");
    Console.WriteLine(ex);
    Console.WriteLine();
    Console.WriteLine("Run TruckTilt.Host as Administrator and try again.");
}

try
{
    Console.WriteLine("Initializing dedicated Interior CAM controller...");
    interiorCameraController = new VirtualController();
    Console.WriteLine("INTERIOR CAM CONTROLLER READY - bind its START button in ETS2/ATS.");
}
catch (Exception ex)
{
    Console.WriteLine("INTERIOR CAM CONTROLLER ERROR: " + ex.Message);
    Console.WriteLine("The steering wheel can continue to operate, but Interior CAM binding is unavailable.");
}

GameAutoBinder.Start();

using var udp = new UdpClient(Port) { EnableBroadcast = true };
var state = InputState.Neutral;
var lastPacket = DateTime.UtcNow;
var sync = new object();
IPEndPoint? phoneEndpoint = null;

void SendHaptic(string message)
{
    IPEndPoint? endpoint;
    lock (sync) endpoint = phoneEndpoint;
    if (endpoint == null) return;

    try
    {
        byte[] data = Encoding.UTF8.GetBytes(message);
        udp.Send(data, data.Length, endpoint);
    }
    catch
    {
        // Haptics are optional and must never interrupt controller operation.
    }
}

using var telemetryHaptics = new TelemetryHaptics(SendHaptic);
telemetryHaptics.Start();

Console.CancelKeyPress += (_, e) =>
{
    e.Cancel = true;
    wheel?.Neutralize();
    try { interiorCameraController?.SetInteriorCamera(false); } catch { }
    telemetryHaptics.Dispose();
    wheel?.Dispose();
    interiorCameraController?.Dispose();
    Environment.Exit(0);
};

_ = Task.Run(async () =>
{
    while (true)
    {
        try
        {
            var result = await udp.ReceiveAsync();
            var message = Encoding.UTF8.GetString(result.Buffer);
            byte[]? response = null;

            if (message.StartsWith("DISCOVER|TruckTilt|", StringComparison.OrdinalIgnoreCase))
            {
                response = Encoding.UTF8.GetBytes($"HELLO|TruckTilt|0.12.6|{Environment.MachineName}");
            }
            else if (message.StartsWith("CONNECT|", StringComparison.OrdinalIgnoreCase))
            {
                lock (sync) phoneEndpoint = result.RemoteEndPoint;
                response = Encoding.UTF8.GetBytes($"WELCOME|{Environment.MachineName}|0.12.6");
            }
            else if (message.StartsWith("PING|", StringComparison.OrdinalIgnoreCase))
            {
                response = Encoding.UTF8.GetBytes("PONG|" + message[5..]);
            }
            else if (message.Equals("INTERIORCAM|PRESS", StringComparison.OrdinalIgnoreCase))
            {
                if (interiorCameraController?.Ready == true)
                {
                    interiorCameraController.SetInteriorCamera(true);
                    Console.WriteLine("Interior CAM: dedicated controller button pressed.");
                    _ = Task.Run(async () =>
                    {
                        await Task.Delay(140);
                        try { interiorCameraController?.SetInteriorCamera(false); }
                        catch (Exception ex) { Console.WriteLine("Interior CAM release error: " + ex.Message); }
                    });
                }
                else
                {
                    Console.WriteLine("Interior CAM: dedicated controller is not ready.");
                }
            }
            else if (message.StartsWith("CAMERA|", StringComparison.OrdinalIgnoreCase))
            {
                if (CameraController.TryParse(message, out var dx, out var dy))
                    CameraController.MoveRelative(dx, dy);
            }
            else if (message.StartsWith("CLICK|LEFT", StringComparison.OrdinalIgnoreCase))
            {
                CameraController.LeftClick();
            }
            else if (message.StartsWith("INPUT|", StringComparison.OrdinalIgnoreCase))
            {
                if (InputState.TryParse(message, out var parsed))
                {
                    lock (sync)
                    {
                        state = parsed;
                        lastPacket = DateTime.UtcNow;
                        phoneEndpoint = result.RemoteEndPoint;
                    }

                    wheel?.Submit(parsed.Steering, parsed.Throttle, parsed.Brake,
                        parsed.Clutch, parsed.Retarder, parsed.ButtonMask);
                }
            }

            if (response is not null)
                await udp.SendAsync(response, response.Length, result.RemoteEndPoint);
        }
        catch (ObjectDisposedException) { break; }
        catch
        {
            // Network errors are intentionally silent; controller safety state
            // is handled by the timeout loop below.
        }
    }
});

while (true)
{
    await Task.Delay(100);
    bool timedOut = false;
    lock (sync)
    {
        if ((DateTime.UtcNow - lastPacket).TotalMilliseconds > TimeoutMs)
        {
            timedOut = state != InputState.Neutral;
            state = InputState.Neutral;
        }
    }
    if (timedOut)
    {
        wheel?.Neutralize();
        Console.WriteLine("SAFETY TIMEOUT - wheel neutralized.");
    }
}

sealed record InputState(
    long Sequence,
    long TimestampMs,
    double Steering,
    double Throttle,
    double Brake,
    double Clutch,
    double Retarder,
    uint ButtonMask)
{
    public static readonly InputState Neutral = new(0, 0, 0, 0, 0, 0, 0, 0);

    public static bool TryParse(string packet, out InputState state)
    {
        state = Neutral;
        try
        {
            var p = packet.Split('|');
            if (p.Length < 40 || !p[0].Equals("INPUT", StringComparison.OrdinalIgnoreCase)) return false;

            uint mask = 0;
            for (int i = 0; i < 32; i++)
                if (p[8 + i] == "1") mask |= 1u << i;

            state = new InputState(
                long.Parse(p[1]), long.Parse(p[2]),
                D(p[3]), D(p[4]), D(p[5]), D(p[6]), D(p[7]), mask);
            return true;
        }
        catch { return false; }
    }

    static double D(string s) => double.Parse(s, System.Globalization.CultureInfo.InvariantCulture);
}
