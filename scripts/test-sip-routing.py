"""Isolated real Kamailio TCP dialog test; never uses production registrations.

Requires Docker's cached Kamailio image. No backend/users/camera needed.
"""
import pathlib
import socket
import subprocess
import time
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]
DOCKER = r"C:\Program Files\Docker\Docker\resources\bin\docker.exe"
IMAGE = "ghcr.io/kamailio/kamailio-ci:5.8.3-alpine"
PORT = 15060
OFFER = "v=0\r\no=probe 1 1 IN IP4 203.0.113.8\r\ns=probe\r\nc=IN IP4 203.0.113.8\r\nt=0 0\r\nm=audio 40000 RTP/AVP 0\r\n"
ANSWER = OFFER.replace("203.0.113.8", "198.51.100.254")


def docker(*args, check=True):
    return subprocess.run([DOCKER, *args], capture_output=True, text=True, check=check)


def headers(message, name):
    return [line.split(":", 1)[1].strip() for line in message.split("\r\n")
            if line.lower().startswith(name.lower() + ":")]


def uri(value):
    return value.split("<", 1)[1].split(">", 1)[0] if "<" in value else value


class Agent:
    def __init__(self, user, local=False):
        self.user = user
        self.sock = socket.create_connection(("127.0.0.1", PORT), timeout=4)
        self.sock.settimeout(4)
        self.port = self.sock.getsockname()[1]
        self.buffer = b""
        # Deliberately unroutable advertised Contact: server must use the real TCP connection.
        self.contact = f"sip:{user}@{'127.0.0.1' if local else '198.51.100.10'}:5092;transport=tcp"

    def send(self, message):
        self.sock.sendall(message.encode())

    def receive(self, start):
        while True:
            while b"\r\n\r\n" not in self.buffer:
                chunk = self.sock.recv(65535)
                if not chunk:
                    raise AssertionError("SIP connection closed")
                self.buffer += chunk
            head, rest = self.buffer.split(b"\r\n\r\n", 1)
            length = int(headers(head.decode(), "Content-Length")[0])
            while len(rest) < length:
                rest += self.sock.recv(65535)
            self.buffer = rest[length:]
            message = (head + b"\r\n\r\n" + rest[:length]).decode()
            if message.startswith(start):
                return message

    def request(self, method, target, call_id, from_value=None, to_value=None, routes=(), cseq=1, body=""):
        return (f"{method} {target} SIP/2.0\r\n"
                f"Via: SIP/2.0/TCP 127.0.0.1:{self.port};branch=z9hG4bK-{uuid.uuid4().hex};rport\r\n"
                f"From: {from_value or f'<sip:{self.user}@lab>;tag={self.user}'}\r\n"
                f"To: {to_value or f'<{target}>'}\r\n"
                f"Call-ID: {call_id}\r\nCSeq: {cseq} {method}\r\n"
                f"Contact: <{self.contact}>\r\nMax-Forwards: 70\r\n"
                + "".join(f"Route: {route}\r\n" for route in routes)
                + ("Content-Type: application/sdp\r\n" if body else "")
                + f"Expires: 300\r\nContent-Length: {len(body.encode())}\r\n\r\n" + body)

    def reply(self, request, body=""):
        result = "SIP/2.0 200 OK\r\n"
        for name in ("Via", "From", "To", "Call-ID", "CSeq", "Record-Route"):
            for value in headers(request, name):
                if name == "To" and ";tag=" not in value:
                    value += ";tag=" + self.user
                result += f"{name}: {value}\r\n"
        self.send(result + f"Contact: <{self.contact}>\r\n"
                  + ("Content-Type: application/sdp\r\n" if body else "")
                  + f"Content-Length: {len(body.encode())}\r\n\r\n" + body)

    def register(self):
        self.send(self.request("REGISTER", "sip:lab", uuid.uuid4().hex,
                               to_value=f"<sip:{self.user}@lab>"))
        self.receive("SIP/2.0 200")


def dialog(caller, callee):
    call_id = uuid.uuid4().hex
    caller.send(caller.request("INVITE", f"sip:{callee.user}@lab", call_id, body=OFFER))
    try:
        invite = callee.receive("INVITE ")
    except TimeoutError as error:
        raise AssertionError("INVITE did not reach the registered TCP connection (unreachable Contact)") from error
    assert invite.endswith(OFFER), "One-to-one offer SDP was unexpectedly rewritten"
    callee.reply(invite, ANSWER)
    answer = caller.receive("SIP/2.0 200")
    assert answer.endswith(ANSWER), "One-to-one answer SDP was unexpectedly rewritten"
    caller.send(caller.request("ACK", uri(headers(answer, "Contact")[0]), call_id,
                              from_value=headers(answer, "From")[0], to_value=headers(answer, "To")[0],
                              routes=list(reversed(headers(answer, "Record-Route")))))
    callee.receive("ACK ")
    # Callee hangs up: exercise reverse-direction routing, not only caller BYE.
    callee.send(callee.request("BYE", uri(headers(invite, "Contact")[0]), call_id,
                              from_value=headers(answer, "To")[0], to_value=headers(invite, "From")[0],
                              routes=headers(invite, "Record-Route"), cseq=2))
    bye = caller.receive("BYE ")
    caller.reply(bye)
    callee.receive("SIP/2.0 200")


def main():
    run_id = uuid.uuid4().hex[:10]
    config = ROOT / "client" / "target" / ("sip-routing-" + run_id)
    config.mkdir(parents=True)
    mcu_port = 15880
    (config / "mcu-answer.sdp").write_bytes(ANSWER.encode())
    (config / "mock-mcu.sh").write_text('''#!/bin/sh
cr=$(printf '\\r')
reply=/tmp/mcu-reply-$$
printf 'SIP/2.0 200 OK\\r\\n' > "$reply"
length=0
while IFS= read -r line; do
    line=${line%"$cr"}
    [ -z "$line" ] && break
    case "$line" in
        Via:*|From:*|Call-ID:*|CSeq:*|Record-Route:*) printf '%s\\r\\n' "$line" >> "$reply" ;;
        To:*) printf '%s;tag=mcu\\r\\n' "$line" >> "$reply" ;;
        Content-Length:*) length=${line#*:}; length=$(printf '%s' "$length" | tr -d ' \\t\\r') ;;
    esac
done
dd bs=1 count="$length" of=/tmp/mock-mcu-offer 2>/dev/null
printf 'Contact: <sip:room-probe@198.51.100.254:15880;transport=tcp>\\r\\nContent-Type: application/sdp\\r\\nContent-Length: %s\\r\\n\\r\\n' "$(wc -c < /etc/kamailio/mcu-answer.sdp)" >> "$reply"
cat "$reply" /etc/kamailio/mcu-answer.sdp
rm -f "$reply"
''', encoding="ascii", newline="\n")
    text = (ROOT / "server/kamailio/kamailio.cfg").read_text(encoding="utf-8")
    # Numeric loopback avoids relying on Docker DNS for the mock MCU; replace
    # its name consistently in both destinations and classification checks.
    (config / "kamailio.cfg").write_text(text.replace("5060", str(PORT)).replace("5080", str(mcu_port))
                                        .replace("freeswitch", "127.0.0.2"), encoding="utf-8")
    (config / "public-ip.defs").write_text(
        '#!substdef "!PUBLIC_IP!198.51.100.254!g"\n#!substdef "!GATEWAY_IP!172.20.0.1!g"\n', encoding="ascii")
    name = "sip-routing-" + run_id
    agents = []
    try:
        docker("run", "-d", "--rm", "--name", name, "-p", f"127.0.0.1:{PORT}:{PORT}/tcp",
               "--add-host", "freeswitch:127.0.0.1",
               "-v", f"{config}:/etc/kamailio:ro", "--entrypoint", "kamailio", IMAGE,
               "-DD", "-E", "-m", "32", "-M", "8")
        for attempt in range(80):
            try:
                agents.append(Agent("probe-alice"))
                break
            except OSError:
                time.sleep(0.1)
        if not agents:
            raise AssertionError("Isolated Kamailio did not start: " + docker("logs", name, check=False).stderr[-2000:])
        docker("exec", "-d", name, "sh", "-c",
               "cp /etc/kamailio/mock-mcu.sh /tmp/mock-mcu.sh && chmod +x /tmp/mock-mcu.sh && exec nc -lk -p 15880 -e /tmp/mock-mcu.sh")
        agents.append(Agent("probe-bob"))
        agents.append(Agent("probe-local", local=True))
        agents.append(Agent("probe-local-two", local=True))
        for agent in agents:
            agent.register()
        for first, second in ((0, 1), (2, 3), (0, 2), (2, 0)):
            dialog(agents[first], agents[second])
        print("PASS: real Kamailio TCP REGISTER, INVITE/200/ACK, reverse BYE/200; remote/local/mixed Contacts")
        for index, expected_offer, expected_answer in ((2, "172.20.0.1", "127.0.0.1"),
                                                      (0, "203.0.113.8", "198.51.100.254")):
            agent = agents[index]
            agent.send(agent.request("INVITE", "sip:room-probe@lab", uuid.uuid4().hex, body=OFFER))
            answer = agent.receive("SIP/2.0 200")
            request = docker("exec", name, "cat", "/tmp/mock-mcu-offer").stdout
            request = request.replace("\r\n", "\n").replace("\n", "\r\n")
            assert f"c=IN IP4 {expected_offer}\r\n" in request, "Wrong room offer media address"
            assert f"c=IN IP4 {expected_answer}\r\n" in answer, "Wrong room answer media address"
        print("PASS: room SDP uses gateway/loopback only for local UA; remote UA keeps campus media addresses")
    except Exception:
        output = docker("logs", name, check=False)
        (config / "proxy.log").write_text(output.stdout + output.stderr, encoding="utf-8")
        state = docker("exec", name, "sh", "-c", "ls -l /tmp/mock-mcu* /tmp/mcu-reply-* 2>/dev/null; cat /tmp/mcu-reply-* 2>/dev/null", check=False)
        (config / "mcu-state.log").write_text(state.stdout + state.stderr, encoding="utf-8")
        print("Isolated proxy diagnostics:", config / "proxy.log")
        raise
    finally:
        for agent in agents:
            agent.sock.close()
        docker("rm", "-f", name, check=False)


if __name__ == "__main__":
    main()
