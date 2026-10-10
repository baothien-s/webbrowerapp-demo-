# VERSION 2 - WebSourceClient (xem source web qua Server)

## Chạy (bật Server trước!)
```bat
run_client.bat
```
hoặc:
```bat
javac WebSourceClient.java
java WebSourceClient
```

## Luồng dùng
1. Ô `Server` để `http://localhost:8080`, bấm `Refresh list` -> thấy 3 web lưu sẵn.
2. Bấm nút nhanh `Facebook` / `Shopee` / `Alibaba` -> client gọi `GET /api/source?url=...`,
   xem `X-Cache: HIT` (lấy từ kho) hay `MISS` (server vừa tải live).
3. Nhập URL mới (vd `https://example.com/`) -> `Xem qua Server` / `Lưu vào Server`.
4. Bấm `Tải trực tiếp` để so sánh (không qua server, Facebook/Shopee thường lỗi 403).
5. 3 tab: `Xem nhanh` (render) + `Source HTML` (coi source) + `Thông tin HTTP`.
6. Double-click 1 dòng trong list để mở lại, `Mở bằng Chrome` để xem bản `/view`.
