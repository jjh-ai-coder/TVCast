#!/usr/bin/env python3
"""迷你 DLNA 控制点：验证 TVCast 接收端（默认指向 adb reverse 的本机端口）。

用法：
  adb reverse tcp:<设备端口> tcp:<设备端口>
  python tools/dlna_probe.py --host 127.0.0.1 --port <设备端口> --callback-host 10.0.2.2 --callback-port 9999
"""
import argparse
import http.server
import re
import socket
import threading
import time

AVT = "urn:schemas-upnp-org:service:AVTransport:1"
RCS = "urn:schemas-upnp-org:service:RenderingControl:1"
CM = "urn:schemas-upnp-org:service:ConnectionManager:1"

received_events = []  # (method, path, headers, body)


class SinkHandler(http.server.BaseHTTPRequestHandler):
    def do_NOTIFY(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8", "replace")
        received_events.append(("NOTIFY", self.path, {k.lower(): v for k, v in self.headers.items()}, body))
        self.send_response(200)
        self.end_headers()

    def log_message(self, *args):
        pass


def raw_request(host, port, method, path, headers=None, body=""):
    s = socket.create_connection((host, port), timeout=10)
    payload = f"{method} {path} HTTP/1.1\r\nHOST: {host}:{port}\r\n"
    for k, v in (headers or {}).items():
        payload += f"{k}: {v}\r\n"
    payload += f"Content-Length: {len(body.encode())}\r\nConnection: close\r\n\r\n{body}"
    s.sendall(payload.encode())
    data = b""
    while True:
        chunk = s.recv(65536)
        if not chunk:
            break
        data += chunk
    s.close()
    text = data.decode("utf-8", "replace")
    head, _, resp_body = text.partition("\r\n\r\n")
    status = int(re.search(r"HTTP/1\.1 (\d+)", head).group(1))
    headers = {}
    for line in head.splitlines()[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k.strip().lower()] = v.strip()
    return status, headers, resp_body


PROBE_HOST = "127.0.0.1"
PROBE_PORT = 0


def soap(action_service, action, args_xml=""):
    body = (
        '<?xml version="1.0" encoding="utf-8"?>'
        '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
        's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
        f'<u:{action} xmlns:u="{action_service}">{args_xml}</u:{action}>'
        "</s:Body></s:Envelope>"
    )
    path = "/control/" + {
        AVT: "avtransport", RCS: "renderingcontrol", CM: "connectionmanager",
    }[action_service]
    return raw_request(
        PROBE_HOST, PROBE_PORT, "POST", path,
        {"Content-Type": 'text/xml; charset="utf-8"', "SOAPACTION": f'"{action_service}#{action}"'},
        body,
    )


def check(name, ok, detail=""):
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f"  -> {detail}" if detail and not ok else ""))
    if not ok:
        raise SystemExit(f"verification failed at: {name}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--callback-host", default="10.0.2.2")
    ap.add_argument("--callback-port", type=int, default=9999)
    ap.add_argument("--media-uri", default="https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4")
    args = ap.parse_args()
    global PROBE_HOST, PROBE_PORT
    PROBE_HOST, PROBE_PORT = args.host, args.port

    sink = http.server.ThreadingHTTPServer(("0.0.0.0", args.callback_port), SinkHandler)
    threading.Thread(target=sink.serve_forever, daemon=True).start()

    # 1. 设备描述
    status, headers, body = raw_request(args.host, args.port, "GET", "/dd.xml")
    check("GET /dd.xml 200", status == 200)
    check("DD 含 MediaRenderer 类型", "MediaRenderer:1" in body)
    check("DD 绝对 controlURL", "control/avtransport" in body)

    # 2. SCPD
    status, _, body = raw_request(args.host, args.port, "GET", "/scpd/avtransport.xml")
    check("GET AVT SCPD 200", status == 200 and "SetAVTransportURI" in body)

    # 3. GetProtocolInfo
    status, _, body = soap(CM, "GetProtocolInfo")
    check("GetProtocolInfo 含 video/mp4", status == 200 and "video/mp4" in body)

    # 4. 订阅 AVT 事件（CALLBACK 指回宿主机）
    status, headers, _ = raw_request(
        args.host, args.port, "SUBSCRIBE", "/event/avtransport",
        {"CALLBACK": f"<http://{args.callback_host}:{args.callback_port}/cb>",
         "NT": "upnp:event", "TIMEOUT": "Second-600"},
    )
    sid = headers.get("sid", "")
    check("SUBSCRIBE 200 + SID", status == 200 and sid.startswith("uuid:"))

    # 5. 推流（隐式播放：只发 SetAVTransportURI 不发 Play）
    status, _, body = soap(AVT, "SetAVTransportURI",
                           "<InstanceID>0</InstanceID>"
                           f"<CurrentURI>{args.media_uri}</CurrentURI>"
                           "<CurrentURIMetaData></CurrentURIMetaData>")
    check("SetAVTransportURI 200", status == 200, body[:200])

    # 6. 等待起播并两次查询进度（应用侧 500ms 缓存一次，两次查询需拉开间隔）
    time.sleep(6)
    status, _, pos1 = soap(AVT, "GetPositionInfo")
    time.sleep(1.5)
    status2, _, pos2 = soap(AVT, "GetPositionInfo")
    r1 = re.search(r"<RelTime>([^<]+)</RelTime>", pos1)
    r2 = re.search(r"<RelTime>([^<]+)</RelTime>", pos2)
    check("GetPositionInfo 有 RelTime", r1 is not None)
    dur = re.search(r"<TrackDuration>([^<]+)</TrackDuration>", pos1)
    print(f"      duration={dur.group(1) if dur else '?'} rel_time={r1.group(1) if r1 else '?'} -> {r2.group(1) if r2 else '?'}")
    check("RelTime 推进（真实播放中）", r1 is not None and r2 is not None and r1.group(1) != r2.group(1))

    # 7. 暂停 → GENA 事件应回传 PAUSED_PLAYBACK
    time.sleep(2)
    status, _, _ = soap(AVT, "Pause")
    time.sleep(2)
    avt_events = [e for e in received_events if "PAUSED_PLAYBACK" in e[3]]
    check("GENA 收到 PAUSED_PLAYBACK 事件", len(avt_events) > 0)
    if avt_events:
        _, path, hdrs, ev_body = avt_events[0]
        check("NOTIFY 头 NT/NTS/SID/SEQ 正确",
              hdrs.get("nt") == "upnp:event" and hdrs.get("nts") == "upnp:propchange" and hdrs.get("sid") == sid)
        seqs = sorted(int(hdrs.get("seq", -1)) for *_, in [])
        seqs_all = sorted(int(h.get("seq", -1)) for _, _, h, _ in received_events)
        print(f"      收到 NOTIFY {len(received_events)} 条, SEQ 序列 = {seqs_all}")
        check("SEQ 从 0 递增", seqs_all[0] == 0 and seqs_all == sorted(seqs_all))

    # 8. 音量
    status, _, _ = soap(RCS, "SetVolume",
                        "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>66</DesiredVolume>")
    status, _, body = soap(RCS, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>")
    check("Set/GetVolume 66 生效", status == 200 and "<CurrentVolume>66</CurrentVolume>" in body)

    # 9. 未知动作容错
    status, _, body = soap(AVT, "X_SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>x</CurrentURI>")
    check("未知动作返回 401 fault", status == 500 and "<errorCode>401</errorCode>" in body)

    print("\nALL CHECKS PASSED")


if __name__ == "__main__":
    main()
