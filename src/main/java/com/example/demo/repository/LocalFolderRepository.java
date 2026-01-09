package com.example.demo.repository;

import com.example.demo.entity.LocalFolder;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LocalFolderRepository extends JpaRepository<LocalFolder, Long> {
    // 查找某账号下的所有文件夹
    List<LocalFolder> findByEmailAccountId(Long emailAccountId);

    // 根据名字查找文件夹 (用于判断是否重名，或查找ID)
    LocalFolder findByEmailAccountIdAndFolderName(Long emailAccountId, String folderName);
}