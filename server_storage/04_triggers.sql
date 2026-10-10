/* =====================================================================
   IS075 - File 04: Trigger chống trùng lịch (lớp bảo vệ thứ hai ở tầng DB)
   Chạy sau 03a và 03b.

   VÌ SAO LÀ TRIGGER "AFTER" (không phải INSTEAD OF):
   - Lớp bảo vệ chính nằm ở các stored procedure (UPDLOCK, HOLDLOCK).
   - Trigger chỉ là lớp thứ hai, phòng khi có người INSERT/UPDATE trực tiếp vào DatCho.
   - Trigger INSTEAD OF làm SCOPE_IDENTITY() trả NULL nên sp_DatCho không lấy được id đơn vừa tạo.
     Trigger AFTER cho mức bảo vệ tương đương: phát hiện trùng thì ROLLBACK và THROW 50007.

   Hành vi:
   - Chỉ kiểm tra các dòng ở trạng thái chiếm chỗ: DA_DUYET (và CHO_DUYET nếu che_do_giu_cho = 1).
   - Chỉ kiểm tra khi INSERT, hoặc khi UPDATE làm đổi tai_nguyen_id, bat_dau, ket_thuc hoặc trang_thai.
   - Phát hiện cả hai dòng chồng nhau ngay trong cùng một câu INSERT nhiều dòng.
   - Có khoảng đệm dem_phut (CauHinh) như các stored procedure.
   - Tự cập nhật updated_at khi UPDATE.

   TẮT/BẬT TẠM THỜI (ví dụ khi nạp dữ liệu mẫu):
       DISABLE TRIGGER dbo.trg_DatCho_ChongTrung ON dbo.DatCho;
       ... nạp dữ liệu ...
       ENABLE TRIGGER dbo.trg_DatCho_ChongTrung ON dbo.DatCho;
   ===================================================================== */

USE IS075;
GO
SET NOCOUNT ON;
GO

CREATE OR ALTER TRIGGER dbo.trg_DatCho_ChongTrung
ON dbo.DatCho
AFTER INSERT, UPDATE
AS
BEGIN
    SET NOCOUNT ON;

    -- Chặn đệ quy (khi cập nhật updated_at ở cuối trigger)
    IF TRIGGER_NESTLEVEL() > 1 RETURN;

    DECLARE @dem INT = dbo.fn_CauHinhInt(N'dem_phut', 10);
    DECLARE @giu_cho INT = dbo.fn_CauHinhInt(N'che_do_giu_cho', 0);

    IF EXISTS (
        SELECT 1
        FROM inserted i
        LEFT JOIN deleted d ON d.id = i.id
        JOIN dbo.DatCho dc
          ON dc.tai_nguyen_id = i.tai_nguyen_id
         AND dc.id <> i.id
         AND (dc.trang_thai = N'DA_DUYET' OR (@giu_cho = 1 AND dc.trang_thai = N'CHO_DUYET'))
         AND dc.bat_dau  < DATEADD(MINUTE,  @dem, i.ket_thuc)
         AND dc.ket_thuc > DATEADD(MINUTE, -@dem, i.bat_dau)
        WHERE (i.trang_thai = N'DA_DUYET' OR (@giu_cho = 1 AND i.trang_thai = N'CHO_DUYET'))
          AND (   d.id IS NULL                              -- INSERT
               OR d.tai_nguyen_id <> i.tai_nguyen_id        -- UPDATE có đổi thông tin liên quan
               OR d.bat_dau       <> i.bat_dau
               OR d.ket_thuc      <> i.ket_thuc
               OR d.trang_thai    <> i.trang_thai)
    )
    BEGIN
        IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
        THROW 50007, N'Khung giờ này đã có người đặt (trùng lịch). Thao tác bị hủy.', 1;
    END

    -- Cập nhật updated_at cho các dòng vừa sửa (chỉ với UPDATE)
    IF EXISTS (SELECT 1 FROM deleted)
    BEGIN
        UPDATE dc
        SET updated_at = SYSUTCDATETIME()
        FROM dbo.DatCho dc
        JOIN inserted i ON i.id = dc.id;
    END
END;
GO

-- Kiểm tra nhanh
SELECT name AS ten_trigger,
       is_disabled AS dang_tat,
       OBJECT_NAME(parent_id) AS tren_bang
FROM sys.triggers
WHERE name = N'trg_DatCho_ChongTrung';
GO

PRINT N'Hoàn tất 04_triggers.sql. Kỳ vọng: 1 dòng, dang_tat = 0, tren_bang = DatCho.';
GO
