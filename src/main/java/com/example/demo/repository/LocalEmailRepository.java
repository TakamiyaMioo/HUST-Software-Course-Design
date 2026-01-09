package com.example.demo.repository;

import com.example.demo.entity.LocalEmail;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LocalEmailRepository extends JpaRepository<LocalEmail, Long> {
    // 查找某个文件夹下的所有邮件
    List<LocalEmail> findByFolderId(Long folderId);

    // 删除文件夹时，把里面的邮件也删了
    void deleteByFolderId(Long folderId);
}