# Fashion Backend - Hệ Thống Quản Lý Thương Mại Điện Tử Thời Trang
---

## 🚀 Công Nghệ Sử Dụng

Dự án được xây dựng với các công nghệ mạnh mẽ và phổ biến nhất hiện nay:

- **Ngôn ngữ:** Java 17
- **Framework:** Spring Boot
- **Cơ sở dữ liệu:** PostgreSQL
- **ORM:** Spring Data JPA (Hibernate)
- **Bảo mật:** Spring Security & JSON Web Token (JWT)
- **Migration:** Flyway
- **AI Integration:** Google Gemini API
- **Công cụ khác:** Maven, Lombok, Spring Mail, Jackson, BCrypt.

---

## ✨ Tính Năng Cốt Lõi

### 1. Xác thực & Phân quyền (Auth)
- Đăng ký, đăng nhập với cơ chế **JWT (Access Token & Refresh Token)**.
- Xác thực email (Email Verification) và Quên mật khẩu (Reset Password).
- Phân quyền người dùng (USER) và quản trị viên (ADMIN).

### 2. Danh mục sản phẩm (Catalog)
- Quản lý sản phẩm với nhiều biến thể (Màu sắc, Kích thước, Hình ảnh riêng theo màu).
- Tìm kiếm nâng cao sử dụng `pg_trgm` (trigram index) trong PostgreSQL.
- Lọc sản phẩm theo giới tính, loại, giá và tag.

### 3. Giỏ hàng & Thanh toán (Cart & Checkout)
- Hỗ trợ giỏ hàng cho khách vãng lai (Guest Cart) thông qua `clientId`.
- Tự động gộp giỏ hàng (Merge Cart) khi khách hàng đăng nhập hoặc đăng ký.
- Quy trình thanh toán kiểm tra tồn kho (Inventory) nghiêm ngặt.

### 4. Quản lý Đơn hàng
- Theo dõi trạng thái đơn hàng (Chờ xử lý, Đã giao, Đã hủy...).
- Lưu snapshot thông tin sản phẩm tại thời điểm mua để bảo toàn lịch sử.
- Gửi email thông báo tự động khi trạng thái đơn hàng thay đổi.

### 5. AI Chat Assistant & Gợi ý sản phẩm
- Tích hợp **Gemini AI** để hỗ trợ tư vấn thời trang và trả lời câu hỏi của khách hàng.
- Hệ thống gợi ý sản phẩm (Recommendation) dựa trên sở thích và hành vi người dùng.

---

## 🏗️ Kiến Trúc Hệ Thống

Dự án tuân thủ kiến trúc phân lớp (Layered Architecture):
- **Controller:** Tiếp nhận và điều phối các yêu cầu API.
- **Service:** Xử lý logic nghiệp vụ tập trung.
- **Repository:** Giao tiếp với cơ sở dữ liệu qua JPA.
- **DTO/Model:** Chuyển đổi và định dạng dữ liệu trả về cho Frontend.
- **Security:** Tầng lọc bảo mật và xác thực không trạng thái (Stateless).

---

## 🛠️ Cài Đặt & Chạy Dự Án

### Yêu cầu hệ thống:
- JDK 17 trở lên.
- PostgreSQL.
- Maven.

### Các bước thực hiện:

1. **Clone repository:**
   ```bash
   git clone https://github.com/your-username/fashion-backend.git
   ```

2. **Cấu hình biến môi trường:**
   Tạo file `.env` hoặc chỉnh sửa `src/main/resources/application.properties` với các thông số:
   - DB URL, Username, Password.
   - Mail server (SMTP).
   - Gemini API Key.
   - JWT Secret Key.

3. **Chạy ứng dụng:**
   ```bash
   mvn spring-boot:run
   ```
   *Flyway sẽ tự động chạy các file migration để tạo cấu trúc bảng trong PostgreSQL.*

---

## 📈 API Endpoints (Sơ lược)
- `POST /api/auth/login`: Đăng nhập hệ thống.
- `GET /api/products`: Lấy danh sách sản phẩm.
- `POST /api/cart/add`: Thêm sản phẩm vào giỏ.
- `POST /api/checkout`: Tạo đơn hàng mới.
- `POST /api/chat`: Tương tác với trợ lý AI.

---

## 👤 Thông tin
- **Tác giả:** Nguyễn Đỗ Quang Đại 
- **Project Name:** Website DA - Fashion Project
- **Role:** Backend Developer
- **Email:** 22028278@vnu.edu.vn / dai12112004@gmail.com

---
*Dự án này được xây dựng cho mục đích học tập và chuẩn bị cho làm đồ án tốt nghiệp.*
