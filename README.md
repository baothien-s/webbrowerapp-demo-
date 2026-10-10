# WEBbrowser_java - Mini Web Browser + HTTP Cache (Java thuần, không cần thư viện ngoài)

Đồ án Lập Trình Mạng: mô hình **Client – Server** xem và lưu trữ source trang web qua HTTP.

| Version | Thư mục | Chạy |
|---|---|---|
| 1. Server lưu trữ web (preload facebook / shopee / alibaba) | `WebServer/` | `run_server.bat` (port 8080) |
| 2. Client kết nối server, coi source web | `WebClient/` | `run_client.bat` (bật server trước) |

## Chạy nhanh
```bat
:: Terminal 1 - Server
cd WebServer
run_server.bat
:: Dashboard: http://localhost:8080/

:: Terminal 2 - Client
cd WebClient
run_client.bat
```

## API (Server -> Client)
- `GET /api/webs` – JSON danh sách web đã lưu
- `GET /api/source?url=...` – source HTML + header `X-Cache: HIT/MISS`
- `POST /api/fetch?url=...` – bắt server tải live + lưu đè
- `GET /view?url=...` – xem web trên Chrome
- `DELETE /api/cache?url=...` – xóa khỏi kho

## Yêu cầu
- JDK 11+ (đã test trên JDK 25), không cần Maven/Gradle.
- Chi tiết mỗi bản xem trong `WebServer/README.md` và `WebClient/README.md`.
