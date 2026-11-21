package tv.mistick.proxy;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.net.DatagramPacket;
import java.net.InetAddress;

/** Разбор SOCKS5-UDP пакетов: нужен для DNS через туннель. */
public class SocksUdpTest {

    private Socks5Server server() {
        return new Socks5Server(null, null, null);
    }

    private DatagramPacket packet(byte[] data) throws Exception {
        return new DatagramPacket(data, data.length,
                InetAddress.getByName("127.0.0.1"), 5000);
    }

    @Test
    public void nonDnsUdpIsDropped() throws Exception {
        byte[] payload = new byte[]{0, 0, 0, 1, 8, 8, 8, 8, 0, 80, 1, 2, 3};
        assertNull("QUIC и прочий UDP не поддерживаем", server().answerDatagram(packet(payload)));
    }

    @Test
    public void shortPacketIsDropped() throws Exception {
        assertNull(server().answerDatagram(packet(new byte[]{0, 0, 0, 1})));
    }

    @Test
    public void dnsRequestIsRecognised() throws Exception {
        // DNS на 8.8.8.8:53 — ответа без сети не будет, но пакет не должен ронять сервер
        byte[] payload = new byte[]{0, 0, 0, 1, 8, 8, 8, 8, 0, 53, 0x12, 0x34};
        assertNull(server().answerDatagram(packet(payload)));
    }
}
