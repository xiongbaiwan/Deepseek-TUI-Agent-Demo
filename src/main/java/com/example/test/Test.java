package com.example.test;

/**
 * @author xbw
 * @Date 2026/5/14 10:07
 * @Description 描述
 */
public class Test {

    /**
     * 计算斐波那契数列的第 n 项（递归实现）
     * @param n 非负整数
     * @return 斐波那契数列的第 n 项
     */
    public int fib(int n) {
        if (n <= 1) {
            return n;
        }
        return fib(n - 1) + fib(n - 2);
    }
}
