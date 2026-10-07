using System.Net.Sockets;
using System.Text;

const int Port = 45454;
const int TimeoutMs = 500;

using var udp = new UdpClient(Port);
udp.EnableBroadcast = true;

Console.Title = "TruckTilt Host";
Console.WriteLine("TruckTilt Host v0.4.1");
Console.WriteLine("====================");
Console.WriteLine($"Listening for TruckTilt phones on UDP {Port}");
Console.WriteLine("Waiting for INPUT packets...");
Console.WriteLine("Keep this window running while using ETS2 / ATS.");
Console.WriteLine("Press Ctrl+C to exit.");
Console.WriteLine();

var state = InputState.Neutral;
var lastPacket = DateTime.UtcNow;
var sync = new object();
long receivedPackets = 0;

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
                response = Encoding.UTF8.GetBytes($"HELLO|TruckTilt|0.4.1|{Environment.MachineName}");
                Console.WriteLine($"DISCOVERY from {result.RemoteEndPoint}");
            }
            else if (message.StartsWith("CONNECT|", StringComparison.OrdinalIgnoreCase))
            {
                response = Encoding.UTF8.GetBytes($"WELCOME|{Environment.MachineName}");
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

                    Console.WriteLine(
                        $"INPUT #{receivedPackets,5}  SEQ {parsed.Sequence,6}  " +
                        $"STEER {parsed.Steering,7:0.000}  THR {parsed.Throttle,5:0.00}  " +
                        $"BRK {parsed.Brake,5:0.00}  " +
                        $"LEFT {(parsed.Left ? "ON " : "OFF")}  " +
                        $"RIGHT {(parsed.Right ? "ON " : "OFF")}  " +
                        $"LIGHTS {(parsed.Lights ? "ON " : "OFF")}  " +
                        $"HORN {(parsed.Horn ? "ON " : "OFF")}");
                }
                else
                {
                    Console.WriteLine($"INPUT RECEIVED BUT INVALID ({message.Split('|').Length} fields): {message}");
                }
            }
            else
            {
                Console.WriteLine($"UNKNOWN PACKET from {result.RemoteEndPoint}: {message}");
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
    long Sequence, long TimestampMs, double Steering, double Throttle, double Brake, double Clutch,
    bool Left, bool Right, bool Lights, bool HighBeam, bool Hazard, bool Horn, bool AirHorn,
    bool Wiper, bool EngineBrake, bool Retarder, bool ParkingBrake, bool Cruise, bool GearUp,
    bool GearDown, bool EngineStart)
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

            // v0.4+: INPUT + seq + timestamp + steering + throttle + brake + clutch
            // + 15 booleans + reserved = 23 fields.
            if (p.Length >= 23)
            {
                state = new InputState(
                    long.Parse(p[1]), long.Parse(p[2]),
                    D(p[3]), D(p[4]), D(p[5]), D(p[6]),
                    B(p[7]), B(p[8]), B(p[9]), B(p[10]), B(p[11]),
                    B(p[12]), B(p[13]), B(p[14]), B(p[15]), B(p[16]),
                    B(p[17]), B(p[18]), B(p[19]), B(p[20]), B(p[21]));
                return true;
            }

            // v0.2/v0.3 compatibility: 21 fields, no clutch.
            if (p.Length >= 21)
            {
                state = new InputState(
                    long.Parse(p[1]), long.Parse(p[2]),
                    D(p[3]), D(p[4]), D(p[5]), 0,
                    B(p[6]), B(p[7]), B(p[8]), B(p[9]), B(p[10]),
                    B(p[11]), B(p[12]), B(p[13]), B(p[14]), B(p[15]),
                    B(p[16]), B(p[17]), B(p[18]), B(p[19]), B(p[20]));
                return true;
            }

            return false;
        }
        catch
        {
            return false;
        }
    }

    static double D(string s) =>
        double.Parse(s, System.Globalization.CultureInfo.InvariantCulture);

    static bool B(string s) => s == "1";
}
