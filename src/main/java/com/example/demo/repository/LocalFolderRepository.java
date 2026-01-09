package com.example.demo.repository;

import com.example.demo.entity.LocalFolder;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LocalFolderRepository extends JpaRepository<LocalFolder, Long> {
    List<LocalFolder> findByEmailAccountId(Long emailAccountId);
    LocalFolder findByEmailAccountIdAndFolderName(Long emailAccountId, String folderName);
}