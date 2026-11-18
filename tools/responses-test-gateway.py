"""Serve a local inference endpoint to the test probes, translating Responses tool namespaces."""

import argparse
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json


def flatten_tools(body):
    tools = []
    names = {}
    for tool in body.get("tools", []):
        if tool["type"] != "namespace":
            tools.append(tool)
            continue
        namespace = tool["name"]
        for member in tool["tools"]:
            if member["type"] != "function":
                raise ValueError("Only function namespaces are supported by this probe")
            qualified = f'{namespace}__{member["name"]}'
            if qualified in names:
                raise ValueError("Tool name collision")
            tools.append({**member, "name": qualified})
            names[qualified] = (namespace, member["name"])
    if len({tool["name"] for tool in tools if "name" in tool}) != sum("name" in tool for tool in tools):
        raise ValueError("Tool name collision")
    body["tools"] = tools
    for item in body["input"]:
        if item["type"] == "function_call" and item.get("namespace"):
            item["name"] = f'{item.pop("namespace")}__{item["name"]}'
    return names


def restore_tool_names(event, names):
    items = event.get("response", {}).get("output", [])
    if "item" in event:
        items = [event["item"], *items]
    for item in items:
        if item["type"] == "function_call" and item.get("name") in names:
            item["namespace"], item["name"] = names[item["name"]]


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path not in ("/v1/chat/completions", "/v1/responses"):
            self.send_error(404)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 2 << 20:
                raise ValueError("Invalid prompt size")
            data = self.rfile.read(length)
            names = {}
            if self.path == "/v1/responses":
                body = json.loads(data)
                names = flatten_tools(body)
                data = json.dumps(body).encode()
        except (KeyError, TypeError, ValueError):
            self.send_error(400)
            return

        client = HTTPConnection("127.0.0.1", self.server.inference_port, timeout=65)
        try:
            client.request("POST", self.path, data, {"Content-Type": "application/json"})
            with client.getresponse() as response:
                content_type = response.getheader("Content-Type", "application/json")
                self.send_response(response.status)
                self.send_header("Content-Type", content_type)
                self.end_headers()
                for line in response:
                    if names and content_type.startswith("text/event-stream") and line.startswith(b"data: {"):
                        event = json.loads(line[6:])
                        restore_tool_names(event, names)
                        line = b"data: " + json.dumps(event).encode() + b"\n"
                    self.wfile.write(line)
                    self.wfile.flush()
        except (OSError, ValueError):
            self.close_connection = True
        finally:
            client.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inference_port", type=int)
    parser.add_argument("listen_port", type=int)
    args = parser.parse_args()
    with ThreadingHTTPServer(("127.0.0.1", args.listen_port), Handler) as server:
        server.inference_port = args.inference_port
        server.serve_forever()


if __name__ == "__main__":
    main()
