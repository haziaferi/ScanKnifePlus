"""Minimal client for VectorCraft's JSON-lines control channel (vectorcraft --control 7979)."""
import json
import socket


class VC:
    def __init__(self, port=7979):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=60)
        self.file = self.sock.makefile("rwb")
        self.next = 1

    def raw(self, method, **params):
        self.next += 1
        self.file.write((json.dumps({"id": self.next, "method": method, "params": params}) + "\n").encode())
        self.file.flush()
        reply = json.loads(self.file.readline())
        if reply.get("error") or reply.get("ok") is False:
            raise RuntimeError(f"{method}: {reply}")
        return reply.get("result", reply)

    def __call__(self, command, **params):
        return self.raw("engine.execute", command=command, params=params)

    def documents(self):
        return self.raw("ui.inspect")["documents"]

    def activate(self, title):
        """Make the named document active and prove it, so an edit can never land on another file."""
        match = [k for k, d in enumerate(self.documents()) if d["title"] == title]
        assert match, f"{title} is not open"
        self("document.activate", index=match[0])
        tree = self.raw("document.inspect", depth=8)
        assert (tree.get("path") or "").replace("\\", "/").endswith(title), f"active document is {tree.get('path')}, not {title}"
        return tree
