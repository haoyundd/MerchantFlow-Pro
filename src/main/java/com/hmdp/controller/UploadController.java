package com.hmdp.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.annotation.RateLimit;
import com.hmdp.annotation.RateLimitType;
import com.hmdp.dto.Result;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    /** 本地演示环境允许的上传大小；生产环境还应在网关和 Spring 配置层再次限制。 */
    private static final long MAX_IMAGE_SIZE = 5 * 1024 * 1024;

    /** 只接受常见图片后缀，避免把上传接口当作任意文件落盘接口。 */
    // 项目 Docker 构建基线是 Java 8，不能使用 Java 9 才提供的 Set.of。
    private static final Set<String> ALLOWED_SUFFIXES = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp"
    ));

    @PostMapping("blog")
    @RateLimit(max = 10, windowSeconds = 60, type = RateLimitType.USER)
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        if (image == null || image.isEmpty()) {
            return Result.fail("上传文件不能为空");
        }
        if (image.getSize() > MAX_IMAGE_SIZE) {
            return Result.fail("图片大小不能超过 5MB");
        }
        try {
            // 获取原始文件名称
            String originalFilename = image.getOriginalFilename();
            String suffix = getSafeSuffix(originalFilename);
            if (suffix == null) {
                return Result.fail("仅支持 jpg、jpeg、png、gif、webp 图片");
            }
            // 生成新文件名
            String fileName = createNewFileName(suffix);
            // 保存文件
            image.transferTo(new File(SystemConstants.IMAGE_UPLOAD_DIR, fileName));
            // 返回结果
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        Path root = Paths.get(SystemConstants.IMAGE_UPLOAD_DIR).toAbsolutePath().normalize();
        String relativeName = filename == null ? "" : filename.replace('\\', '/');
        while (relativeName.startsWith("/")) {
            relativeName = relativeName.substring(1);
        }
        Path filePath = root.resolve(relativeName).normalize();
        // 必须仍位于图片根目录内，并且只允许删除本接口生成的 blogs 子目录文件。
        if (!filePath.startsWith(root) || !relativeName.startsWith("blogs/")
                || Files.isDirectory(filePath)) {
            return Result.fail("错误的文件名称");
        }
        FileUtil.del(filePath.toFile());
        return Result.ok();
    }

    /**
     * 从原始文件名提取安全后缀，不信任用户传入的完整路径或文件名。
     *
     * @return 小写合法后缀；文件名为空、无后缀或后缀不在白名单时返回 null
     */
    private String getSafeSuffix(String originalFilename) {
        if (StrUtil.isBlank(originalFilename)) {
            return null;
        }
        String baseName = Paths.get(originalFilename).getFileName().toString();
        String suffix = StrUtil.subAfter(baseName, ".", true);
        suffix = suffix == null ? "" : suffix.toLowerCase(Locale.ROOT);
        return ALLOWED_SUFFIXES.contains(suffix) ? suffix : null;
    }

    /** 只使用 UUID 和白名单后缀生成路径，下一步由控制器把它交给本地文件存储。 */
    private String createNewFileName(String suffix) {
        // 生成目录
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        // 判断目录是否存在
        File dir = new File(SystemConstants.IMAGE_UPLOAD_DIR, StrUtil.format("/blogs/{}/{}", d1, d2));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // 生成文件名
        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }
}
