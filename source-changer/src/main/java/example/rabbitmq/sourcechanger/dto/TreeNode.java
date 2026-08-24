package example.rabbitmq.sourcechanger.dto;

import java.util.List;

/** One node of a workspace file tree: a folder with children or a file. */
public record TreeNode(
        String type,
        String name,
        String path,
        Long size,
        List<TreeNode> children
) {

    public static TreeNode folder(String name, String path, List<TreeNode> children) {
        return new TreeNode("folder", name, path, null, children);
    }

    public static TreeNode file(String name, String path, long size) {
        return new TreeNode("file", name, path, size, null);
    }
}
