package com.example.cal;

/**
 * 冒泡排序算法实现
 * 
 * 冒泡排序（Bubble Sort）是一种简单的排序算法。
 * 它重复地遍历要排序的数列，依次比较相邻两个元素，
 * 如果顺序错误就把它们交换过来，直到没有元素需要交换为止。
 * 
 * 时间复杂度: O(n²)
 * 空间复杂度: O(1)
 */
public class BubbleSort {

    /**
     * 对整型数组进行冒泡排序（从小到大）
     *
     * @param arr 待排序的整型数组
     */
    public static void sort(int[] arr) {
        if (arr == null || arr.length <= 1) {
            return;
        }

        int n = arr.length;
        // 外层循环：控制排序轮数
        for (int i = 0; i < n - 1; i++) {
            // 优化标志：如果某一轮没有发生交换，说明已经有序，提前结束
            boolean swapped = false;

            // 内层循环：相邻元素比较交换
            for (int j = 0; j < n - 1 - i; j++) {
                if (arr[j] > arr[j + 1]) {
                    // 交换 arr[j] 和 arr[j+1]
                    int temp = arr[j];
                    arr[j] = arr[j + 1];
                    arr[j + 1] = temp;
                    swapped = true;
                }
            }

            // 如果没有发生交换，数组已有序，提前退出
            if (!swapped) {
                break;
            }
        }
    }

    /**
     * 打印数组内容
     *
     * @param arr 要打印的数组
     */
    public static void printArray(int[] arr) {
        if (arr == null) {
            System.out.println("null");
            return;
        }
        System.out.print("[");
        for (int i = 0; i < arr.length; i++) {
            System.out.print(arr[i]);
            if (i < arr.length - 1) {
                System.out.print(", ");
            }
        }
        System.out.println("]");
    }

    /**
     * main 方法 — 演示冒泡排序
     */
    public static void main(String[] args) {
        // 测试用例 1：普通乱序
        int[] arr1 = {64, 34, 25, 12, 22, 11, 90};
        System.out.print("排序前: ");
        printArray(arr1);
        sort(arr1);
        System.out.print("排序后: ");
        printArray(arr1);

        System.out.println();

        // 测试用例 2：已有序数组
        int[] arr2 = {1, 2, 3, 4, 5};
        System.out.print("排序前: ");
        printArray(arr2);
        sort(arr2);
        System.out.print("排序后: ");
        printArray(arr2);

        System.out.println();

        // 测试用例 3：包含重复元素
        int[] arr3 = {5, 3, 8, 3, 1, 5};
        System.out.print("排序前: ");
        printArray(arr3);
        sort(arr3);
        System.out.print("排序后: ");
        printArray(arr3);
    }
}
