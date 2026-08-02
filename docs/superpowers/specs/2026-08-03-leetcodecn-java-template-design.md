---
title: LeetCodeCN Java 代码模板优化设计
date: 2026-08-03
status: plan
source: 用户个人刷题模板（Velocity）
scope: 单模板优化
---

# LeetCodeCN Java 代码模板优化设计

## 背景

CodeEpiphany 用 Apache Velocity 渲染用户配置的代码模板，生成刷题文件。用户当前在插件设置中配置了一份 LeetCodeCN 的 Java 模板，希望对其进行优化。

渲染上下文（`VelocityUtils.scala`）注入：
- `challenge`：题目信息（`name` / `frontendQuestionId` / `difficulty` / `codeDojo` / `slug` / `code` 等）
- `beginRegion` / `endRegion`：由 `language.createComment(...)` 预渲染的单行注释标记
- `velocity`：`VelocityTool`（`camelCaseName` / `smallCamelCaseName` / `snakeCaseName` / `dateTime`）

## 提交机制（关键约束）

插件提交代码时调用 `Language.scala#extractCodeFromRegion`：扫描文件，**只提取 `beginRegion` 与 `endRegion` 两个标记行之间的内容**发给 LeetCode。

- 标记内容：`//IMPORTANT!! Submit Code Region Begin(Do not remove this line)` / 对应 End
- 缺失标记 → 提交内容为空或错误 → 提交失败

文件名规则（`leetcodecn/filename/java.ft`）：`${velocity.camelCaseName(${challenge.slug})}`，例如 `TwoSum.java`。

## 现状问题清单

| 级别 | 问题 | 说明 |
|---|---|---|
| 🔴 关键 | 没有 `${beginRegion}`/`${endRegion}` | 提交时提取不到区域，提交必失败 |
| 🟡 中 | `solution.new Solution()` 假设非静态内部类 | LeetCode 的 `${challenge.code}` 是 `class Solution {}`，嵌套后需 `new Xxx().new Solution()` |
| 🟡 中 | 类名 `${velocity.camelCaseName($challenge.slug)}` 对数字开头 slug 不安全 | 如 `3sum` → `3sum`，非法 Java 类名 |
| 🟡 中 | `printArray` 仅支持 `int[]` | 覆盖太窄，常遇 List/ListNode/TreeNode/矩阵 |
| 🟢 轻 | 无题目回溯链接 | 可加 `https://leetcode.cn/problems/${challenge.slug}/` |

## 设计目标

1. 修复提交区域缺失的致命缺陷。
2. 内置常用本地调试辅助方法，降低手写成本。
3. 保留用户偏好的"外层类包 Solution"骨架。
4. 规范文件头，便于复盘。

## 选定方案

**结构（用户已确认）**：外层类 `Xxx` 包裹 `class Solution`，`main` 与辅助方法位于外层类。

- `Solution` 是 `${challenge.code}` 原样嵌入的非静态嵌套类 → main 中 `new Xxx().new Solution()`。
- `ListNode` / `TreeNode` 定义为外层类的 **`static` 嵌套类**：
  - 可被 static 辅助方法（`buildList` / `buildTree`）直接 `new`；
  - 可被同在外层类内的 `Solution` 以简单名引用；
  - LeetCode 后台自带的同名类不影响本地编译；用不上时无害挂载。
- `${beginRegion}` / `${challenge.code}` / `${endRegion}` 带正常缩进融入外层类；提交时提取的代码带极少量前导缩进，LeetCode 按语法解析不受影响。

**辅助方法（用户已确认全选）**：全部 `private static`，置于外层类。

- 数组：`printArray(int[])` / `printArray(Object[])` / `printMatrix(int[][])` / `printMatrix(char[][])`
- 集合：`printList(List<?>)` / `printNestedList(List<? extends List<?>>)`
- 链表：`ListNode` 定义 / `buildList(int[])` / `printList(ListNode)`
- 二叉树：`TreeNode` 定义 / `buildTree(Integer[])`（层序含 null）/ `printTree(TreeNode)`（层序，自动裁剪尾部 null）

## 优化后的模板

```java
import java.util.*;

/**
 * 题目：${challenge.name}（题号：${challenge.frontendQuestionId}）
 * 难度：${challenge.difficulty}
 * 平台：${challenge.codeDojo}
 * 链接：https://leetcode.cn/problems/${challenge.slug}/
 * 生成时间：${velocity.dateTime()}
 *
 * 解题思路：
 *   1.
 *   2.
 *
 * 复杂度分析：
 *   时间复杂度：O()
 *   空间复杂度：O()
 */
class ${velocity.camelCaseName($challenge.slug)} {

    /*
     * 提交区域：插件提交时只上传下面 begin/end 标记之间的代码（即 Solution 本体）。
     * 切勿删除这两个标记行，否则提交会失败。
     */
    ${beginRegion}
    ${challenge.code}
    ${endRegion}

    public static void main(String[] args) {
        Solution solution = new ${velocity.camelCaseName($challenge.slug)}().new Solution();

        // TODO: 调用 solution.xxx(...) 并用下方辅助方法打印结果验证，例如：
        // System.out.println(printArray(solution.twoSum(new int[]{2, 7, 11, 15}, 9)));
    }

    // ===================== 链表节点（本地调试用）=====================
    static class ListNode {
        int val;
        ListNode next;
        ListNode() {}
        ListNode(int val) { this.val = val; }
        ListNode(int val, ListNode next) { this.val = val; this.next = next; }
    }

    // ===================== 二叉树节点（本地调试用）=====================
    static class TreeNode {
        int val;
        TreeNode left;
        TreeNode right;
        TreeNode() {}
        TreeNode(int val) { this.val = val; }
        TreeNode(int val, TreeNode left, TreeNode right) {
            this.val = val;
            this.left = left;
            this.right = right;
        }
    }

    // -------------------- 数组 --------------------
    private static String printArray(int[] arr) {
        if (arr == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            sb.append(arr[i]);
            if (i < arr.length - 1) sb.append(", ");
        }
        return sb.append("]").toString();
    }

    private static String printArray(Object[] arr) {
        return arr == null ? "null" : Arrays.toString(arr);
    }

    private static String printMatrix(int[][] matrix) {
        if (matrix == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < matrix.length; i++) {
            sb.append(printArray(matrix[i]));
            if (i < matrix.length - 1) sb.append(", ");
        }
        return sb.append("]").toString();
    }

    private static String printMatrix(char[][] matrix) {
        if (matrix == null) return "null";
        StringBuilder sb = new StringBuilder("[\n");
        for (char[] row : matrix) sb.append("  ").append(new String(row)).append('\n');
        return sb.append("]").toString();
    }

    // -------------------- 集合 --------------------
    private static String printList(List<?> list) {
        return list == null ? "null" : list.toString();
    }

    private static String printNestedList(List<? extends List<?>> nested) {
        if (nested == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < nested.size(); i++) {
            sb.append(nested.get(i) == null ? "null" : nested.get(i));
            if (i < nested.size() - 1) sb.append(", ");
        }
        return sb.append("]").toString();
    }

    // -------------------- 链表 --------------------
    private static ListNode buildList(int[] vals) {
        if (vals == null || vals.length == 0) return null;
        ListNode dummy = new ListNode(0), cur = dummy;
        for (int v : vals) { cur.next = new ListNode(v); cur = cur.next; }
        return dummy.next;
    }

    private static String printList(ListNode head) {
        if (head == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (ListNode p = head; p != null; p = p.next) sb.append(p.val).append(" -> ");
        return sb.append("null").toString();
    }

    // -------------------- 二叉树 --------------------
    private static TreeNode buildTree(Integer[] vals) {
        if (vals == null || vals.length == 0 || vals[0] == null) return null;
        TreeNode root = new TreeNode(vals[0]);
        Queue<TreeNode> queue = new ArrayDeque<>();
        queue.offer(root);
        int i = 1;
        while (i < vals.length) {
            TreeNode node = queue.poll();
            if (node == null) break;
            if (i < vals.length && vals[i] != null) { node.left = new TreeNode(vals[i]); queue.offer(node.left); }
            i++;
            if (i < vals.length && vals[i] != null) { node.right = new TreeNode(vals[i]); queue.offer(node.right); }
            i++;
        }
        return root;
    }

    private static String printTree(TreeNode root) {
        if (root == null) return "[]";
        List<String> out = new ArrayList<>();
        Queue<TreeNode> queue = new ArrayDeque<>();
        queue.offer(root);
        while (!queue.isEmpty()) {
            TreeNode node = queue.poll();
            if (node == null) { out.add("null"); continue; }
            out.add(String.valueOf(node.val));
            queue.offer(node.left);
            queue.offer(node.right);
        }
        while (!out.isEmpty() && "null".equals(out.get(out.size() - 1))) out.remove(out.size() - 1);
        return "[" + String.join(", ", out) + "]";
    }
}
```

## 已知限制

1. **slug 数字开头**：`${velocity.camelCaseName($challenge.slug)}` 对 `3sum` 这类 slug 生成非法 Java 类名。LeetCodeCN slug 基本为英文开头，极少触发；真遇到手动改类名即可。`VelocityTool` 层面无首字符前缀处理，本设计不改。
2. **`printList` 重载歧义**：`printList(List<?>)` 与 `printList(ListNode)` 共存，传字面 `null` 时编译器无法消解，需显式 `printList((ListNode) null)`。日常调用带具体参数，无影响。

## 交付物与范围

- **主交付物**：上述优化后的模板字符串，供用户粘贴到 CodeEpiphany 的 LeetCodeCN 代码模板设置中。
- **可选（待用户决定）**：是否同步更新仓库默认模板 `src/main/resources/templates/leetcodecn/code/java.ft`（当前默认模板极为简洁，不含辅助方法）。

## 不做（YAGNI）

- 不改 `VelocityTool`（不加 slug 首字符前缀处理）。
- 不引入 `${challenge.description}` / `${challenge.testCases}`：前者会让文件头极长，后者格式因题而异难以通用注入。
- 不做条件性包含辅助方法：Velocity 模板难以按题型判断，统一内置最简单，用不上无害。
- 不改 LeetCode（国际站）模板：本次仅针对 LeetCodeCN；如需可后续同套用。
