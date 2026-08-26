/**
 * 1.6.0:LeetCode 官方知识点(tag)静态清单——题库页「知识点」筛选 chips 数据源。
 *
 * 2026-08-26 自 leetcode.cn problemsetQuestionList 全量扫描(4421 题)收集,
 * 仅收录带官方中文名(nameTranslated 非空)的 72 个精选主题;niche 英文标签
 * 不进筛选面板。筛选值 = slug(与后端 ProblemFilter.tags / 平台侧协议一致,
 * 见 LeetCodeApi.fetchQuestionPageCn 的 filters.tags)。
 */
export interface LcTag {
  slug: string
  zh: string
  en: string
}

export const LC_TAGS: LcTag[] = [
  { slug: "binary-search", zh: "二分查找", en: "Binary Search" },
  { slug: "binary-search-tree", zh: "二叉搜索树", en: "Binary Search Tree" },
  { slug: "binary-tree", zh: "二叉树", en: "Binary Tree" },
  { slug: "interactive", zh: "交互", en: "Interactive" },
  { slug: "bitmask", zh: "位掩码", en: "Bitmask" },
  { slug: "bit-manipulation", zh: "位运算", en: "Bit Manipulation" },
  { slug: "geometry", zh: "几何", en: "Geometry" },
  { slug: "divide-and-conquer", zh: "分治", en: "Divide and Conquer" },
  { slug: "prefix-sum", zh: "前缀和", en: "Prefix Sum" },
  { slug: "dynamic-programming", zh: "动态规划", en: "Dynamic Programming" },
  { slug: "monotonic-stack", zh: "单调栈", en: "Monotonic Stack" },
  { slug: "monotonic-queue", zh: "单调队列", en: "Monotonic Queue" },
  { slug: "game-theory", zh: "博弈", en: "Game Theory" },
  { slug: "doubly-linked-list", zh: "双向链表", en: "Doubly-Linked List" },
  { slug: "two-pointers", zh: "双指针", en: "Two Pointers" },
  { slug: "biconnected-component", zh: "双连通分量", en: "Biconnected Component" },
  { slug: "suffix-array", zh: "后缀数组", en: "Suffix Array" },
  { slug: "hash-function", zh: "哈希函数", en: "Hash Function" },
  { slug: "hash-table", zh: "哈希表", en: "Hash Table" },
  { slug: "backtracking", zh: "回溯", en: "Backtracking" },
  { slug: "graph", zh: "图", en: "Graph" },
  { slug: "radix-sort", zh: "基数排序", en: "Radix Sort" },
  { slug: "heap-priority-queue", zh: "堆（优先队列）", en: "Heap (Priority Queue)" },
  { slug: "concurrency", zh: "多线程", en: "多线程" },
  { slug: "trie", zh: "字典树", en: "Trie" },
  { slug: "string", zh: "字符串", en: "String" },
  { slug: "string-matching", zh: "字符串匹配", en: "String Matching" },
  { slug: "union-find", zh: "并查集", en: "Union Find" },
  { slug: "breadth-first-search", zh: "广度优先搜索", en: "Breadth-First Search" },
  { slug: "strongly-connected-component", zh: "强连通分量", en: "Strongly Connected Component" },
  { slug: "merge-sort", zh: "归并排序", en: "Merge Sort" },
  { slug: "quickselect", zh: "快速选择", en: "Quickselect" },
  { slug: "sweep-line", zh: "扫描线", en: "Sweep Line" },
  { slug: "rejection-sampling", zh: "拒绝采样", en: "Rejection Sampling" },
  { slug: "topological-sort", zh: "拓扑排序", en: "Topological Sort" },
  { slug: "sorting", zh: "排序", en: "Sorting" },
  { slug: "sort", zh: "排序", en: "Sort" },
  { slug: "math", zh: "数学", en: "Math" },
  { slug: "database", zh: "数据库", en: "数据库" },
  { slug: "data-stream", zh: "数据流", en: "Data Stream" },
  { slug: "array", zh: "数组", en: "Array" },
  { slug: "number-theory", zh: "数论", en: "Number Theory" },
  { slug: "minimum-spanning-tree", zh: "最小生成树", en: "Minimum Spanning Tree" },
  { slug: "shortest-path", zh: "最短路", en: "Shortest Path" },
  { slug: "ordered-set", zh: "有序集合", en: "Ordered Set" },
  { slug: "minimax-algorithm", zh: "极小化极大", en: "Minimax" },
  { slug: "enumeration", zh: "枚举", en: "Enumeration" },
  { slug: "stack", zh: "栈", en: "Stack" },
  { slug: "tree", zh: "树", en: "Tree" },
  { slug: "binary-indexed-tree", zh: "树状数组", en: "Binary Indexed Tree" },
  { slug: "bucket-sort", zh: "桶排序", en: "Bucket Sort" },
  { slug: "probability-and-statistics", zh: "概率与统计", en: "Probability and Statistics" },
  { slug: "simulation", zh: "模拟", en: "Simulation" },
  { slug: "eulerian-circuit", zh: "欧拉回路", en: "Eulerian Circuit" },
  { slug: "reservoir-sampling", zh: "水塘抽样", en: "Reservoir Sampling" },
  { slug: "depth-first-search", zh: "深度优先搜索", en: "Depth-First Search" },
  { slug: "sliding-window", zh: "滑动窗口", en: "Sliding Window" },
  { slug: "rolling-hash", zh: "滚动哈希", en: "Rolling Hash" },
  { slug: "matrix", zh: "矩阵", en: "Matrix" },
  { slug: "segment-tree", zh: "线段树", en: "Segment Tree" },
  { slug: "combinatorics", zh: "组合数学", en: "Combinatorics" },
  { slug: "brainteaser", zh: "脑筋急转弯", en: "Brainteaser" },
  { slug: "counting", zh: "计数", en: "Counting" },
  { slug: "counting-sort", zh: "计数排序", en: "Counting Sort" },
  { slug: "memoization", zh: "记忆化", en: "Memoization" },
  { slug: "design", zh: "设计", en: "Design" },
  { slug: "greedy", zh: "贪心", en: "Greedy" },
  { slug: "iterator", zh: "迭代器", en: "Iterator" },
  { slug: "recursion", zh: "递归", en: "Recursion" },
  { slug: "linked-list", zh: "链表", en: "Linked List" },
  { slug: "queue", zh: "队列", en: "Queue" },
  { slug: "randomized", zh: "随机化", en: "Randomized" },
]

/** 展示名:中文优先,英文兜底。 */
export function lcTagName(tag: LcTag): string {
  return tag.zh || tag.en
}
