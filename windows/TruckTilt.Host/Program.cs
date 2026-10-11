using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text;
using TruckTilt.Host;

const int Port = 45454;
const int TimeoutMs = 500;
bool debugMode = args.Any(a => a.Equals("--debug", StringComparison.OrdinalIgnoreCase) || a.Equals("-d", StringComparison.OrdinalIgnoreCase));
HostLog.Initialize(debugMode);

Console.Title = debugMode ? "TruckTilt Host - Debug" : "TruckTilt Host";
HostLog.Public("TruckTilt Host v0.12.6");
HostLog.Public("Starting virtual steering wheel...");
HostLog.Public(debugMode ? "Debug mode: ON (detailed diagnostics enabled)." : "Public mode: clean status display. Use --debug for diagnostics.");

VirtualWheel? wheel = null;
try
{
    HostLog.Debug("Initializing HIDMaestro...");
    wheel = new VirtualWheel();
    HostLog.Public("Virtual steering wheel: READY");
    HostLog.Public("Phone: WAITING FOR CONNECTION");
    HostLog.Public("Game: WAITING FOR ETS2 / ATS");
}
catch (Exception ex)
{
    HostLog.Error("Virtual steering wheel failed to initialize. Run as Administrator and check the diagnostic log.", ex);
}

// Manual in-game binding only: do not inject or auto-bind game controls.

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
    telemetryHaptics.Dispose();
    wheel?.Dispose();
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
                bool firstConnection;
                lock (sync)
                {
                    firstConnection = phoneEndpoint is null || !phoneEndpoint.Equals(result.RemoteEndPoint);
                    phoneEndpoint = result.RemoteEndPoint;
                }
                if (firstConnection) HostLog.Public("Phone: CONNECTED");
                response = Encoding.UTF8.GetBytes($"WELCOME|{Environment.MachineName}|0.12.6");
            }
            else if (message.StartsWith("PING|", StringComparison.OrdinalIgnoreCase))
            {
                response = Encoding.UTF8.GetBytes("PONG|" + message[5..]);
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

string? lastGameStatus = null;
DateTime lastGameCheck = DateTime.MinValue;
while (true)
{
    await Task.Delay(100);
    if ((DateTime.UtcNow - lastGameCheck).TotalSeconds >= 1)
    {
        lastGameCheck = DateTime.UtcNow;
        string? gameStatus = Process.GetProcessesByName("eurotrucks2").Length > 0 ? "ETS2 DETECTED" :
                             Process.GetProcessesByName("amtrucks").Length > 0 ? "ATS DETECTED" : "WAITING FOR ETS2 / ATS";
        if (!string.Equals(gameStatus, lastGameStatus, StringComparison.Ordinal))
        {
            lastGameStatus = gameStatus;
            HostLog.Public("Game: " + gameStatus);
        }
    }
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
        HostLog.Public("Safety timeout: controls neutralized (phone input lost).");
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
    ulong ButtonMask)
{
    public static readonly InputState Neutral = new(0, 0, 0, 0, 0, 0, 0, 0UL);

    public static bool TryParse(string packet, out InputState state)
    {
        state = Neutral;
        try
        {
            var p = packet.Split('|');
            if (p.Length < 41 || !p[0].Equals("INPUT", StringComparison.OrdinalIgnoreCase)) return false;

            ulong mask = 0;
            for (int i = 0; i < 33; i++)
                if (p[8 + i] == "1") mask |= 1UL << i;

            state = new InputState(
                long.Parse(p[1]), long.Parse(p[2]),
                D(p[3]), D(p[4]), D(p[5]), D(p[6]), D(p[7]), mask);
            return true;
        }
        catch { return false; }
    }

    static double D(string s) => double.Parse(s, System.Globalization.CultureInfo.InvariantCulture);
}
