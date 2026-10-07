using System.Net;
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

var state = new InputState();
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

            lock (sync)
            {
                if (message.StartsWith("DISCOVER|TruckTilt|", StringComparison.OrdinalIgnoreCase))
                {
                    var hello = $"HELLO|TruckTilt|0.3|{Environment.MachineName}";
                    var bytes = Encoding.UTF8.GetBytes(hello);
                    await udp.SendAsync(bytes, bytes.Length, result.RemoteEndPoint);
                }
                else if (message.StartsWith("CONNECT|", StringComparison.OrdinalIgnoreCase))
                {
                    var welcome = $"WELCOME|{Environment.MachineName}";
                    var bytes = Encoding.UTF8.GetBytes(welcome);
                    await udp.SendAsync(bytes, bytes.Length, result.RemoteEndPoint);
                }
                else if (message.StartsWith("PING|", StringComparison.OrdinalIgnoreCase))
                {
                    var pong = Encoding.UTF8.GetBytes("PONG|" + message[5..]);
                    await udp.SendAsync(pong, pong.Length, result.RemoteEndPoint);
                }
                else if (message.StartsWith("INPUT|"))
                {
                    if (InputState.TryParse(message, out var parsed))
                    {
                        state = parsed;
                        lastPacket = DateTime.UtcNow;
                        Console.WriteLine(
                            $"STEER {state.Steering,7:0.000}  THR {state.Throttle,5:0.00}  " +
                            $"BRK {state.Brake,5:0.00}  HORN {(state.Horn ? "ON " : "OFF")}  " +
                            $"HAZ {(state.Hazard ? "ON " : "OFF")}");
                    }
                }
            }
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
        0, 0, 0, 0, 0, false, false, false, false, false,
        false, false, false, false, false, false, false, false,
        false, false);

    public static bool TryParse(string packet, out InputState state)
    {
        state = Neutral;
        try
        {
            var p = packet.Split('|');
            if (p.Length < 21) return false;

            state = new InputState(
                long.Parse(p[1]),
                long.Parse(p[2]),
                double.Parse(p[3], System.Globalization.CultureInfo.InvariantCulture),
                double.Parse(p[4], System.Globalization.CultureInfo.InvariantCulture),
                double.Parse(p[5], System.Globalization.CultureInfo.InvariantCulture),
                p[6] == "1", p[7] == "1", p[8] == "1", p[9] == "1",
                p[10] == "1", p[11] == "1", p[12] == "1", p[13] == "1",
                p[14] == "1", p[15] == "1", p[16] == "1", p[17] == "1",
                p[18] == "1", p[19] == "1", p[20] == "1");
            return true;
        }
        catch
        {
            return false;
        }
    }
}
