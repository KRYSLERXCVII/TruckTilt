using System.Net;
using System.Net.Sockets;
using System.Text;
using TruckTilt.Host;

const int Port = 45454;
const int TimeoutMs = 500;

Console.Title = "TruckTilt Host";
Console.WriteLine("TruckTilt Host v0.7.3 — ETS2 DirectInput Wheel");
Console.WriteLine("================================================");
Console.WriteLine($"Listening for TruckTilt phones on UDP {Port}");
Console.WriteLine("Device: TruckTilt Virtual Steering Wheel (DirectInput/HID)");
Console.WriteLine("Axes: Steering / Throttle / Brake / Clutch / Retarder");
Console.WriteLine("Buttons: 32 independent HID buttons");
Console.WriteLine("Press Ctrl+C to exit.");
Console.WriteLine();

VirtualWheel? wheel = null;
try
{
    Console.WriteLine("Initializing HIDMaestro...");
    wheel = new VirtualWheel();
    Console.WriteLine("VIRTUAL STEERING WHEEL READY");
    Console.WriteLine("Open joy.cpl and look for TruckTilt Virtual Steering Wheel.");
    Console.WriteLine("Then bind the axes/buttons in ETS2 / ATS.");
}
catch (Exception ex)
{
    Console.WriteLine();
    Console.WriteLine("VIRTUAL WHEEL ERROR");
    Console.WriteLine(ex);
    Console.WriteLine();
    Console.WriteLine("Run TruckTilt.Host as Administrator and try again.");
}

using var udp = new UdpClient(Port) { EnableBroadcast = true };
var state = InputState.Neutral;
var lastPacket = DateTime.UtcNow;
var lastEndpoint = "";
var sync = new object();
long receivedPackets = 0;
long invalidPackets = 0;

Console.CancelKeyPress += (_, e) =>
{
    e.Cancel = true;
    wheel?.Neutralize();
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
                response = Encoding.UTF8.GetBytes($"HELLO|TruckTilt|0.7.2|{Environment.MachineName}");
                if (lastEndpoint != result.RemoteEndPoint.ToString())
                {
                    lastEndpoint = result.RemoteEndPoint.ToString();
                    Console.WriteLine($"DISCOVERY from {lastEndpoint}");
                }
            }
            else if (message.StartsWith("CONNECT|", StringComparison.OrdinalIgnoreCase))
            {
                response = Encoding.UTF8.GetBytes($"WELCOME|{Environment.MachineName}|0.7.3");
                Console.WriteLine($"CONNECTED {result.RemoteEndPoint}");
            }
            else if (message.StartsWith("PING|", StringComparison.OrdinalIgnoreCase))
            {
                response = Encoding.UTF8.GetBytes("PONG|" + message[5..]);
            }
            else if (message.StartsWith("INPUT|", StringComparison.OrdinalIgnoreCase))
            {
                Interlocked.Increment(ref receivedPackets);
                if (InputState.TryParse(message, out var parsed))
                {
                    lock (sync)
                    {
                        state = parsed;
                        lastPacket = DateTime.UtcNow;
                    }

                    wheel?.Submit(parsed.Steering, parsed.Throttle, parsed.Brake,
                        parsed.Clutch, parsed.Retarder, parsed.ButtonMask);

                    if (receivedPackets == 1 || receivedPackets % 60 == 0)
                    {
                        Console.WriteLine(
                            $"INPUT #{receivedPackets,6}  " +
                            $"STEER {parsed.Steering,7:0.000}  " +
                            $"THR {parsed.Throttle,5:0.00}  " +
                            $"BRK {parsed.Brake,5:0.00}  " +
                            $"CLT {parsed.Clutch,5:0.00}  " +
                            $"RET {parsed.Retarder,5:0.00}  " +
                            $"BTN 0x{parsed.ButtonMask:X8}");
                    }
                }
                else
                {
                    Interlocked.Increment(ref invalidPackets);
                    Console.WriteLine($"INVALID INPUT: {message}");
                }
            }

            if (response is not null)
                await udp.SendAsync(response, response.Length, result.RemoteEndPoint);
        }
        catch (ObjectDisposedException) { break; }
        catch (Exception ex) { Console.Error.WriteLine("Network error: " + ex.Message); }
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
        Console.WriteLine("SAFETY TIMEOUT — wheel neutralized.");
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
    public static readonly InputState Neutral = new(0,0,0,0,0,0,0,0);

    public static bool TryParse(string packet, out InputState state)
    {
        state = Neutral;
        try
        {
            var p = packet.Split('|');
            // INPUT + seq + timestamp + 5 analogs + 32 buttons = 40 fields.
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
