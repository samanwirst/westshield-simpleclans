"""Manual remote clients: no host credentials, OP, filesystem writes or console API.

The owner publishes a short-lived read-only control document. Responses use the
normal vanilla help command and are observed in the owning server's private log.
Every sequence executes once. An unknown acknowledgement must be observed, never
redispatched under a new sequence. The only secret is a temporary player password.
"""
import base64
import io
import json
import os
import re
import socket
import struct
import threading
import time
import zlib
from urllib.parse import urlparse

import requests
from clan_protocol_client import Client, status, vint

URL = os.environ['WIUT_CONTROL_URL']
NAMES = json.loads(os.environ['WIUT_NAMES'])
BEACON = os.environ['WIUT_BEACON']
PASSWORD = os.environ['WIUT_TEST_PASSWORD']
assert len(NAMES) == 3 and all(re.fullmatch(r'Wt(?:Own|Mem|Oth)[0-9]{4}', name) for name in NAMES)
assert re.fullmatch(r'[a-f0-9]{16}', BEACON) and len(PASSWORD) >= 16
parsed = urlparse(URL)
assert parsed.scheme == 'https' and parsed.netloc == 'wiut-voice.dynmap.xyz'
assert re.fullmatch(r'/map/wiut-live-[a-f0-9]{32}\.json', parsed.path)

clients = {}
cursors = {}
authenticated = set()
last_command = {}
protocol = status('wiut.minecraft.surf', 25565)['version']['protocol']

class Capture(io.RawIOBase):
    def __init__(self, stream):
        self.stream = stream
        self.value = bytearray()
    def read(self, size=-1):
        data = self.stream.read(size)
        self.value.extend(data)
        return data
    def close(self): self.stream.close()

def connect(name):
    client = Client('wiut.minecraft.surf', 25565, name, protocol, virtual_host='wiut.minecraft.surf')
    original_send = client.send
    lock = threading.RLock()
    def send(*args, **kwargs):
        with lock: original_send(*args, **kwargs)
    client.send = send
    original_command = client.command
    def command(text):
        # Stay below the native command rate limit without granting OP/bypasses.
        time.sleep(max(0, last_command.get(name, 0) + 1.1 - time.monotonic()))
        last_command[name] = time.monotonic()
        original_command(text)
    client.command = command
    # Capture open-screen receipts without changing the production plugin.
    from clan_protocol_client import vi
    import zlib
    original_read = client.read
    original_stream = client.stream
    capture = Capture(original_stream)
    client.stream = capture
    def read_packet():
        capture.value.clear()
        state = client.state
        compressed = client.compression
        original_read()
        if state != 'game': return
        data = io.BytesIO(bytes(capture.value))
        size = vi(data)
        body = data.read(size)
        if compressed:
            data = io.BytesIO(body)
            unpacked = vi(data)
            body = zlib.decompress(data.read()) if unpacked else data.read()
        data = io.BytesIO(body)
        if vi(data) == 59: client.messages.append(('open-screen', data.read().hex()))
    client.read = read_packet
    clients[name] = client
    cursors[name] = 0
    def pump():
        try:
            while not client.closed: client.pump(.2)
        except (OSError, ValueError): client.closed = True
    threading.Thread(target=pump, daemon=True).start()
    for _ in range(120):
        if client.state == 'game':
            client.send(44)
            time.sleep(.5)
            return client
        if client.closed: raise RuntimeError('Client disconnected before game: ' + name)
        time.sleep(.1)
    raise TimeoutError('Game session not confirmed')

def close(client):
    client.closed = True
    try: client.socket.shutdown(socket.SHUT_RDWR)
    except OSError: pass
    client.close()

def drain():
    result = {}
    for name, client in clients.items():
        messages = client.messages[cursors[name]:]
        cursors[name] = len(client.messages)
        result[name] = [(kind, str(value)[:1600]) for kind, value in messages[-16:]
                        if kind in {'chat', 'disconnect', 'open-screen', 'clan-chat-raw', 'game'}
                        and 'WIUTRPC' not in str(value)]
    return result

def emit(sequence, value):
    payload = base64.urlsafe_b64encode(zlib.compress(json.dumps(value, separators=(',', ':')).encode())).decode()
    chunks = [payload[index:index+140] for index in range(0, len(payload), 140)]
    name = min((name for name in authenticated if not clients[name].closed), key=lambda name: last_command.get(name, 0))
    beacon = clients[name]
    for index, chunk in enumerate(chunks):
        beacon.command(f'minecraft:help WIUTRPC{BEACON}_{sequence}_{index}_{len(chunks)}_{chunk}')
        time.sleep(.04)

def perform(action):
    kind = action['kind']
    assert kind in {'connect', 'command', 'register', 'login', 'send', 'drain', 'close', 'finish'}
    name = action.get('name', NAMES[0])
    assert name in NAMES
    if kind == 'connect':
        if name not in clients or clients[name].closed: connect(name)
        return {'state': 'game'}
    if kind in {'finish', 'drain'}: return True
    client = clients[name]
    if kind == 'command':
        text = action['text']
        assert len(text) <= 220 and not text.startswith(('register ', 'login '))
        client.command(text)
        time.sleep(.7)
    elif kind == 'register':
        client.command('register ' + PASSWORD + ' ' + PASSWORD)
        time.sleep(.7)
    elif kind == 'login':
        client.command('login ' + PASSWORD)
        time.sleep(.7)
        authenticated.add(name)
    elif kind == 'send':
        packet = action['packet']
        assert packet in {0, 9, 19, 41, 44, 53, 66}
        payload = base64.b64decode(action['payload'], validate=True)
        assert len(payload) <= 2048
        client.send(packet, payload)
        time.sleep(.7)
    return True

try:
    for name in NAMES: connect(name)
    # One authenticated account carries private log acknowledgements. The other
    # two remain unauthenticated until the controller explicitly logs them in.
    first = clients[NAMES[0]]
    first.command('register ' + PASSWORD + ' ' + PASSWORD)
    time.sleep(.8)
    first.command('login ' + PASSWORD)
    time.sleep(.8)
    authenticated.add(NAMES[0])
    emit(0, {'ok': True, 'protocol': protocol, 'messages': drain()})
    print('READY: temporary clients connected; no host credentials loaded.', flush=True)
    last = 0
    started = time.monotonic()
    while time.monotonic() - started < 1000 and last < 500:
        try:
            response = requests.get(URL, params={'poll': time.time_ns()}, headers={'Cache-Control': 'no-cache'}, timeout=10)
            response.raise_for_status()
            action = response.json()
        except (requests.RequestException, ValueError):
            time.sleep(.3)
            continue
        sequence = action.get('sequence', 0)
        if sequence <= last:
            time.sleep(.15)
            continue
        assert sequence == last + 1, 'Refuse skipped or reordered controls'
        last = sequence  # Claim before execution; never repeat an unknown outcome.
        try:
            value = perform(action)
            result = {'ok': True, 'value': value, 'messages': drain()}
        except Exception as error:
            result = {'ok': False, 'error': (type(error).__name__ + ': ' + str(error)).replace(PASSWORD, '<redacted>'), 'messages': drain()}
        emit(sequence, result)
        print(f'Observed sequence {sequence}: {action["kind"]}; success={result["ok"]}', flush=True)
        if action['kind'] == 'close':
            close(clients[action['name']])
            if all(client.closed for client in clients.values()): break
        if action['kind'] == 'finish': break
finally:
    for client in clients.values():
        if not client.closed: close(client)
    print('Temporary protocol sessions closed.', flush=True)
