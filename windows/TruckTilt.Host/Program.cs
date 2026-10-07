using System.Net.Sockets;
using System.Text;

const int Port = 45454;
const int TimeoutMs = 500;

using var udp = new UdpClient(Port);
udp.EnableBroadcast = true;

Console.Title = "TruckTilt Host";
Console.WriteLine("TruckTilt Host v0.4");
Console.WriteLine("=================");
Console.WriteLine($"Listening for TruckTilt phones on UDP {Port}");
Console.WriteLine("Keep this window running while using ETS2 / ATS.");
Console.WriteLine("Press Ctrl+C to exit.");
Console.WriteLine();

var state = InputState.Neutral;
var lastPacket = DateTime.UtcNow;
var sync = new object();

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
                response = Encoding.UTF8.GetBytes($"HELLO|TruckTilt|0.4|{Environment.MachineName}");
            }
            else if (message.StartsWith("CONNECT|", StringComparison.OrdinalIgnoreCase))
            {
                response = Encoding.UTF8.GetBytes($"WELCOME|{Environment.MachineName}");
            }
            else if (message.StartsWith("PING|", StringComparison.OrdinalIgnoreCase))
            {
                response = Encoding.UTF8.GetBytes("PONG|" + message[5..]);
            }
            else if (message.StartsWith("INPUT|", StringComparison.OrdinalIgnoreCase) &&
                     InputState.TryParse(message, out var parsed))
            {
                lock (sync)
                {
                    state = parsed;
                    lastPacket = DateTime.UtcNow;
                }

                Console.WriteLine(
                    $"SEQ {parsed.Sequence,6}  " +
                    $"STEER {parsed.Steering,7:0.000}  " +
                    $"THR {parsed.Throttle,5:0.00}  " +
                    $"BRK {parsed.Brake,5:0.00}  " +
                    $"LEFT {(parsed.Left ? "ON " : "OFF")}  " +
                    $"RIGHT {(parsed.Right ? "ON " : "OFF")}  " +
                    $"LIGHTS {(parsed.Lights ? "ON " : "OFF")}  " +
                    $"HORN {(parsed.Horn ? "ON " : "OFF")}");
            }

            if (response is not null)
                await udp.SendAsync(response, response.Length, result.RemoteEndPoint);
        }
        catch (ObjectDisposedException)
        {
            break;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("Network error: " + ex.Message);
        }
    }
});

while (true)
{
    await Task.Delay(100);

    lock (sync)
    {
        if ((DateTime.UtcNow - lastPacket).TotalMilliseconds > TimeoutMs)
        {
            if (state != InputState.Neutral)
                Console.WriteLine("SAFETY TIMEOUT — inputs neutralized.");

            state = InputState.Neutral;
        }
    }
}

sealed record InputState(
    long Sequence,
    long TimestampMs,
    double Steering,
    double Throttle,
    double Brake,
    double Clutch,
    bool Left,
    bool Right,
    bool Lights,
    bool HighBeam,
    bool Hazard,
    bool Horn,
    bool AirHorn,
    bool Wiper,
    bool EngineBrake,
    bool Retarder,
    bool ParkingBrake,
    bool Cruise,
    bool GearUp,
    bool GearDown,
    bool EngineStart)
{
    public static readonly InputState Neutral = new(
        0, 0, 0, 0, 0, 0,
        false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false);

    public static bool TryParse(string packet, out InputState state)
    {
        state = Neutral;

        try
        {
            var p = packet.Split('|');

            // INPUT + 6 numeric fields + 15 booleans + reserved = 23 fields.
            if (p.Length < 23)
                return false;

            state = new InputState(
                long.Parse(p[1]),
                long.Parse(p[2]),
                double.Parse(p[3], System.Globalization.CultureInfo.InvariantCulture),
                double.Parse(p[4], System.Globalization.CultureInfo.InvariantCulture),
                double.Parse(p[5], System.Globalization.CultureInfo.InvariantCulture),
                double.Parse(p[6], System.Globalization.CultureInfo.InvariantCulture),

                p[7] == "1",   // left
                p[8] == "1",   // right
                p[9] == "1",   // lights
                p[10] == "1",  // high beam
                p[11] == "1",  // hazard
                p[12] == "1",  // horn
                p[13] == "1",  // air horn
                p[14] == "1",  // wiper
                p[15] == "1",  // engine brake
                p[16] == "1",  // retarder
                p[17] == "1",  // parking brake
                p[18] == "1",  // cruise
                p[19] == "1",  // gear up
                p[20] == "1",  // gear down
                p[21] == "1"); // engine start/stop

            return true;
        }
        catch
        {
            return false;
        }
    }
}
