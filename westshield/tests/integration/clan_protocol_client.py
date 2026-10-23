"""Bounded Minecraft 26.2 network probe using packet IDs from the server jar."""
import socket,struct,json,zlib,time,io,uuid,select
import nbtlib

def vint(n):
 n &= 0xffffffff;out=bytearray()
 while True:
  b=n &127;n >>=7;out.append(b|(128 if n else 0))
  if not n:return bytes(out)
def vi(stream):
 n=0
 for i in range(5):
  b=stream.read(1)
  if not b:raise EOFError()
  n|=(b[0]&127)<<(7*i)
  if not b[0]&128:return n
 raise ValueError('Invalid varint')
def string(s):
 raw=s.encode();return vint(len(raw))+raw
class Client:
 def __init__(self,host,port,username,protocol,virtual_host=None):
  self.socket=socket.create_connection((host,port),timeout=5);self.stream=self.socket.makefile('rb',buffering=0);self.compression=False;self.state='login';self.messages=[];self.closed=False
  self.send(0,vint(protocol)+string(virtual_host or host)+struct.pack('>H',port)+vint(2));self.send(0,string(username)+uuid.uuid4().bytes)
 def send(self,pid,payload=b''):
  body=vint(pid)+payload
  if self.compression:body=vint(0)+body
  self.socket.sendall(vint(len(body))+body)
 def command(self,command):self.send(7,string(command))
 def read(self):
  size=vi(self.stream);raw=b''
  while len(raw)<size:
   part=self.stream.read(size-len(raw))
   if not part:raise EOFError()
   raw+=part
  if self.compression:
   f=io.BytesIO(raw);unpacked=vi(f);raw=zlib.decompress(f.read()) if unpacked else f.read()
  f=io.BytesIO(raw);pid=vi(f);body=f.read()
  if self.state=='game' and b'WESTSHIELD_CLAN_CHAT_CHECK' in body:self.messages.append(('clan-chat-raw',body.hex()))
  if self.state=='login':
   if pid==3:self.compression=True
   elif pid==2:self.send(3);self.state='config'
   elif pid==0:self.closed=True;self.messages.append(('disconnect',body.hex()));return
  elif self.state=='config':
   if pid==14:self.send(7,vint(0))
   elif pid==3:self.send(3);self.state='game';self.messages.append(('game','joined'))
   elif pid==4:self.send(4,body)
   elif pid==5:self.send(5,body)
   elif pid==2:self.closed=True;self.messages.append(('disconnect',body.hex()))
  else:
   if pid==44:self.send(28,body)
   elif pid==72:
    f=io.BytesIO(body);tid=vi(f);self.send(0,vint(tid))
   elif pid==11:self.send(11,struct.pack('>f',10.0))
   elif pid in [121,32,87]:
    f=io.BytesIO(body);tag=f.read(1)[0];d=nbtlib.tag.Base.get_tag(tag).parse(f);self.messages.append(('disconnect' if pid==32 else 'actionbar' if pid==87 else 'chat',str(d)))
    if pid==32:self.closed=True
 def pump(self,seconds):
  end=time.monotonic()+seconds
  while time.monotonic()<end and not self.closed:
   if not select.select([self.socket],[],[],max(0,end-time.monotonic()))[0]:break
   self.socket.settimeout(5)
   try:self.read()
   except socket.timeout:break
   except EOFError:self.closed=True;break
  return self.messages
 def close(self):self.stream.close();self.socket.close()
def status(host,port,virtual_host=None):
 s=socket.create_connection((host,port),timeout=8);f=s.makefile('rb')
 def send(body):s.sendall(vint(len(body))+body)
 send(vint(0)+vint(777)+string(virtual_host or host)+struct.pack('>H',port)+vint(1));send(vint(0));n=vi(f);b=io.BytesIO(f.read(n));vi(b);j=json.loads(b.read(vi(b)).decode());f.close();s.close();return j
