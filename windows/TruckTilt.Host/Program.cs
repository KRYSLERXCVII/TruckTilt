using System.Net.Sockets;
using System.Text;

const int Port = 45454;
const int TimeoutMs = 500;

using var udp = new UdpClient(Port);
udp.EnableBroadcast = true;

Console.Title = "TruckTilt Host";
Console.WriteLine("TruckTilt Host");
Console.WriteLine("==============");
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
                response = Encoding.UTF8.GetBytes($"HELLO|TruckTilt|0.3.1|{Environment.MachineName}");
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
                    $"STEER {parsed.Steering,7:0.000}  THR {parsed.Throttle,5:0.00}  " +
                    $"BRK {parsed.Brake,5:0.00}  HORN {(parsed.Horn ? "ON " : "OFF")}  " +
                    $"HAZ {(parsed.Hazard ? "ON " : "OFF")}");
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
            state = InputState.Neutral;
    }
}

sealed record InputState(
    long Sequence, long TimestampMs, double Steering, double Throttle, double Brake,
    bool Left, bool Right, bool Lights, bool HighBeam, bool Hazard, bool Horn,
    bool AirHorn, bool Wiper, bool EngineBrake, bool Retarder, bool ParkingBrake,
    bool Cruise, bool GearUp, bool GearDown, bool EngineStart)
{
    public static readonly InputState Neutral = new(
        0, 0, 0, 0, 0, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false);

    public static bool TryParse(string packet, out InputState state)
    {
        state = Neutral;
        try
        {
            var p = packet.Split('|');
            if (p.Length < 21) return false;

            state = new InputState(
                long.Parse(p[1]), long.Parse(p[2]),
                double.Parse(p[3], System.Globalization.CultureInfo.InvariantCulture),
                double.Parse(p[4], System.Globalization.CultureInfo.InvariantCulture),
                double.Parse(p[5], System.Globalization.CultureInfo.InvariantCulture),
                p[6] == "1", p[7] == "1", p[8] == "1", p[9] == "1",
                p[10] == "1", p[11] == "1", p[12] == "1", p[13] == "1",
                p[14] == "1", p[15] == "1", p[16] == "1", p[17] == "1",
                p[18] == "1", p[19] == "1", p[20] == "1");
            return true;
        }
        catch { return false; }
    }
}
