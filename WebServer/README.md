# VERSION 1 - WebCacheServer (kho lưu trữ web HTTP)

## Chạy console
```bat
run_server.bat
```
hoặc:
```bat
javac WebCacheServer.java
java WebCacheServer        :: port 8080
```
Dashboard: `http://localhost:8080/` (mở bằng Chrome)

## Chạy GUI quản lý (khuyên dùng)
```bat
run_server_gui.bat
```
hoặc:
```bat
javac WebCacheServer.java ServerManagerGUI.java
java ServerManagerGUI
```
GUI gồm: nút Start/Stop + đèn trạng thái, thống kê Requests/HIT/MISS,
danh sách web trong kho (click để xem nhanh + coi source),
thêm URL / fetch live / xóa cache, log server realtime.

## API cho Client
| Method | URL | Tác dụng |
|---|---|---|
| GET | `/api/webs` | JSON danh sách web đã lưu |
| GET | `/api/source?url=...` | Lấy source HTML + header `X-Cache: HIT/MISS` |
| POST | `/api/fetch?url=...` | Bắt server tải live + lưu đè |
| GET | `/view?url=...` | Xem web như thật trên Chrome |
| DELETE | `/api/cache?url=...` | Xóa khỏi kho |

## 3 web lưu sẵn
- `https://facebook.com/` (snapshot demo)
- `https://shopee.vn/` (snapshot demo)
- `https://www.alibaba.com/` (snapshot demo)

Lý do snapshot: Facebook/Shopee/Alibaba chặn bot + cần login/JS nên không fetch live ổn định.
Các web tĩnh như `https://example.com/` thì server tải live bình thường.

## Lưu trữ (folder storage/)
- Mỗi lần save (fetch live / client bấm Lưu / GUI thêm URL) app **tự tạo folder `storage/` + 1 file `.html`** cho URL đó.
- Restart server tự nạp lại hết file trong `storage/`.
- GUI có nút **Nhập folder...**: chọn 1 folder bất kỳ chứa nhiều file `.html/.htm` -> nạp hết vào kho.
  App tự nhận URL gốc nếu file có marker `<!-- URL: https://... -->` hoặc `<link rel="canonical">`,
  không có thì đặt `local://ten-file`.
- Nút **Mở folder**: mở `storage/` trên Explorer để coi file trực tiếp.

## Tự động nạp file mới (watcher)
- Server **tự theo dõi folder `storage/`**: chỉ cần copy/thả 1 file `.html` vào là app **tự setup**,
  nạp ngay vào kho (log `IMPORT ... -> ...`, nguồn `watch`), không cần restart hay bấm gì thêm.
  GUI đang mở cũng tự thấy sau ~2s (chế độ tự refresh).
- Muốn file nhận đúng URL gốc thì chèn marker `<!-- URL: https://... -->` hoặc `<link rel="canonical">`
  trong `<head>`, không có thì app đặt `local://ten-file`.
