# Kết quả kiểm chứng
 
**Công nghệ:** Java 21, Spring Boot 4.0.9, Spring Cloud Gateway 5.0.2, PostgreSQL, Redis, MinIO và Docker Compose.

## 1. Kết quả tổng quan

- **Build:** Cả 3 project (Gateway, User Service, Order Service) build thành công và tạo executable JAR.
- **Unit test:** 18/18 đạt (Gateway: 8; User: 6; Order: 4).
- **Integration test:** 4 nhóm đạt trên các dependency thực; 6 container có trạng thái `healthy`.
- **Database:** Flyway migration và Hibernate schema validation thành công; dữ liệu vẫn tồn tại sau khi khởi động lại. Hai service không có quyền kết nối chéo database.

| Yêu cầu | Kết quả kiểm chứng |
|---|---|
| Routing | `/users/**` và `/orders/**` đến đúng service. |
| JWT / Offloading | Gateway kiểm tra JWT; các API được bảo vệ từ chối request không hợp lệ. |
| Redis Rate Limiting | Giới hạn 10 token/giây, có HTTP `429` khi vượt ngưỡng và phục hồi sau đó. |
| Aggregation | `/dashboard` trả thông tin user và các đơn hàng tương ứng; kiểm tra quyền sở hữu. |
| MinIO Valet Key | Upload bằng pre-signed PUT URL thành công; URL hết hạn sau 300 giây bị trả `403`. |

## 2. Benchmark độ trễ

Chạy lệnh:

```powershell
python .\tests\benchmark.py --samples 100 --warmup 10 --rps 4
```

Mỗi đường gọi có **100 mẫu**, sau **10 lần warmup**; tổng cộng **400 request được đo**. Sử dụng IPv4, HTTP/1.1 keep-alive, cùng user/JWT/database và luân phiên thứ tự gọi direct/gateway. Tải qua Gateway dưới ngưỡng giới hạn; **100% request thành công, không có `429`**.

| Endpoint | Đường gọi | Average (ms) | P50 (ms) | P95 (ms) |
|---|---|---:|---:|---:|
| `/users/me` | Trực tiếp | 12.651 | 9.597 | 24.151 |
| `/users/me` | Qua Gateway | 21.274 | 20.351 | 32.872 |
| `/orders/me` | Trực tiếp | 13.156 | 12.990 | 21.880 |
| `/orders/me` | Qua Gateway | 21.989 | 21.081 | 35.613 |

**Chênh lệch Average:** User **+8.623 ms** (~68,2%); Order **+8.833 ms** (~67,1%). Đây là số liệu localhost trên Windows 11/Docker Desktop ở tải thấp, **không đại diện cho hiệu năng production**.

## 3. Chạy lại và kiểm thử

Từ thư mục gốc `apigatewaylab` (đã cấu hình `.env`):

```powershell
docker compose -f docker-compose.yml -f tests/docker-compose.test.yml up -d --build --wait --wait-timeout 240
docker compose -f docker-compose.yml -f tests/docker-compose.test.yml ps
.\tests\Build-Lab.ps1
python .\tests\integration.py
python .\tests\benchmark.py --samples 100 --warmup 10 --rps 4
```

**Gateway:** `http://localhost:8080`. PostgreSQL dùng cổng host **15435** trong file override kiểm thử do cổng **5435** đang được project khác sử dụng; cổng nội bộ container vẫn là `5432`.

**Bằng chứng:** `tests/results/integration*.json`, `tests/results/benchmark.{json,csv}` và các log trong `.build/` (các file sinh ra này được gitignore). Hướng dẫn API và cách chạy xem [README](../README.md).

## 4. Giới hạn kiểm chứng

- Chưa chạy thủ công 3 executable JAR ngoài Docker.
- Chưa kiểm thử tải lớn, nhiều Gateway instance hoặc client từ máy khác.
- Benchmark chỉ đánh giá độ trễ ở tải thấp; chưa xác định throughput tối đa.

**Kết luận:** Hệ thống đáp ứng cả 5 yêu cầu chức năng theo các phép kiểm thử được ghi nhận. Việc định tuyến và xử lý bảo mật ở Gateway làm tăng độ trễ trung bình khoảng **8,6–8,8 ms** trong môi trường thử nghiệm.
