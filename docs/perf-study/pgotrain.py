import http.client, subprocess, sys, time, os, json
exe, cwd, raw, rounds = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
env = dict(os.environ, LLVM_PROFILE_FILE=raw, NABU_PROFILE_WRITE_EVERY='5', NABU_PROFILE_VERBOSE='1')
p = subprocess.Popen([exe], cwd=cwd, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
c = None
t0 = time.time()
while time.time() - t0 < 300:
    try:
        c = http.client.HTTPConnection('127.0.0.1', 8080, timeout=10)
        c.request('GET', '/pet/2'); r = c.getresponse(); r.read()
        if r.status == 200: break
    except Exception:
        time.sleep(0.2)
def call(m, path, body=None, ctype='application/json'):
    h = {'Content-Type': ctype} if body is not None else {}
    c.request(m, path, body=body, headers=h); r = c.getresponse(); r.read(); return r.status
gets = ['/pet/2', '/pet/2', '/pet/2', '/pet/findByStatus?status=available', '/pet/findByStatus?status=available',
        '/store/inventory', '/store/inventory', '/pet/1', '/pet/findByTags?tags=friendly', '/store/order/1',
        '/user/user1', '/pet/999']
for rnd in range(rounds):
    for _ in range(100):
        for g in gets:
            call('GET', g)
    pid = 900 + rnd
    body = json.dumps({"id": pid, "name": "T%d" % rnd, "status": "available", "photoUrls": ["u"],
                       "category": {"id": 81, "name": "c"}, "tags": [{"id": 81, "name": "tt"}]})
    call('POST', '/pet', body); call('PUT', '/pet', body); call('GET', '/pet/%d' % pid)
    call('POST', '/pet/%d?name=x&status=pending' % pid, None); call('DELETE', '/pet/%d' % pid)
    for g in ('/v3/api-docs', '/swagger-ui/index.html'):
        call('GET', g)
time.sleep(12)  # at least one periodic write after the last request
subprocess.run(['taskkill', '/F', '/PID', str(p.pid)], capture_output=True)
print('trained', rounds, 'rounds; profraw bytes', os.path.getsize(raw) if os.path.exists(raw) else None)
