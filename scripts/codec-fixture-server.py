"""Read-only DAV/Range fixture server, reached by a USB device through adb reverse."""
import argparse
import html
import http.server
import os
import pathlib
import urllib.parse

parser = argparse.ArgumentParser()
parser.add_argument("--directory", required=True)
parser.add_argument("--port", type=int, default=8765)
args = parser.parse_args()
root = pathlib.Path(args.directory).resolve()

class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_PROPFIND(self):
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        entries = ["<d:response><d:href>/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>"]
        for path in sorted(root.iterdir()):
            if path.suffix.lower() not in (".ape", ".m4a", ".cue"):
                continue
            entries.append("<d:response><d:href>/" + urllib.parse.quote(path.name) + "</d:href><d:propstat><d:prop><d:displayname>" + html.escape(path.name) + "</d:displayname><d:getcontentlength>" + str(path.stat().st_size) + "</d:getcontentlength><d:getlastmodified>fixture-v1</d:getlastmodified><d:resourcetype/></d:prop></d:propstat></d:response>")
        body = ('<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">' + "".join(entries) + "</d:multistatus>").encode()
        self.send_response(207)
        self.send_header("Content-Type", "application/xml; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = (root / urllib.parse.unquote(urllib.parse.urlsplit(self.path).path).lstrip("/")).resolve()
        if path.parent != root or not path.is_file():
            self.send_error(404)
            return
        length = path.stat().st_size
        start, end = 0, length - 1
        requested = self.headers.get("Range")
        if requested:
            first, last = requested.removeprefix("bytes=").split("-", 1) if hasattr(str, "removeprefix") else requested[6:].split("-", 1)
            start, end = int(first), min(int(last) if last else end, end)
        if start > end:
            self.send_error(416)
            return
        self.send_response(206 if requested else 200)
        self.send_header("Content-Type", "audio/mp4" if path.suffix == ".m4a" else "application/octet-stream")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end-start+1))
        self.send_header("Connection", "close")
        if requested:
            self.send_header("Content-Range", f"bytes {start}-{end}/{length}")
        self.end_headers()
        try:
            with path.open("rb") as source:
                source.seek(start)
                remaining = end-start+1
                while remaining:
                    data = source.read(min(remaining, 16384))
                    if not data:
                        break
                    self.wfile.write(data)
                    remaining -= len(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

http.server.ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
