"""A stand-in for the GitHub releases page of Nabu, for trying the install scripts and self-update (and for the CI
check of a release before it is published).

usage: fake-releases.py <port> <tag> <directory with the release files>
  /releases/latest                  -> 302 to /releases/tag/<tag>
  /releases/download/<tag>/<file>   -> the file in the directory (SHA256SUMS is made from the others)
"""
import hashlib
import http.server
import os
import sys

port, tag, directory = int(sys.argv[1]), sys.argv[2], sys.argv[3]


def sums():
    lines = []
    for name in sorted(os.listdir(directory)):
        if name == 'SHA256SUMS':
            continue
        digest = hashlib.sha256()
        with open(os.path.join(directory, name), 'rb') as f:
            for block in iter(lambda: f.read(1 << 20), b''):
                digest.update(block)
        lines.append(digest.hexdigest() + '  ' + name)
    return ('\n'.join(lines) + '\n').encode()


SUMS = sums()


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        path = self.path
        if path == '/releases/latest':
            self.send_response(302)
            self.send_header('Location', '/releases/tag/' + tag)
            self.end_headers()
        elif path.startswith('/releases/download/' + tag + '/'):
            name = path.rsplit('/', 1)[1]
            if name == 'SHA256SUMS':
                body = SUMS
                self.send_response(200)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            elif os.path.isfile(os.path.join(directory, name)):
                size = os.path.getsize(os.path.join(directory, name))
                self.send_response(200)
                self.send_header('Content-Length', str(size))
                self.end_headers()
                with open(os.path.join(directory, name), 'rb') as f:
                    while True:
                        block = f.read(1 << 20)
                        if not block:
                            break
                        self.wfile.write(block)
            else:
                self.send_error(404)
        else:
            self.send_error(404)

    def log_message(self, *args):
        pass


http.server.ThreadingHTTPServer(('127.0.0.1', port), Handler).serve_forever()
