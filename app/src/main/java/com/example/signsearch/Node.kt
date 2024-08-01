package com.example.signsearch

import java.util.LinkedList

class Node(val char: Char, val row: Int, val col: Int) {
    fun getNeighbours(board: Board): List<Node> {
        return getNeighbours(board) { true }
    }

    fun getNeighbours(board: Board, filter: (Node) -> Boolean): List<Node> {
        val rowStart = Math.max(0, row - 1)
        val rowEnd = Math.min(board.height - 1, row + 1)
        val colStart = Math.max(0, col - 1)
        val colEnd = Math.min(board.width - 1, col + 1)
        val ret = LinkedList<Node>()
        for (row in rowStart..rowEnd) {
            for (col in colStart..colEnd) {
                if (filter(board.grid[row][col]))  {
                    ret.add(board.grid[row][col])
                }
            }
        }
        return ret
    }

    override fun equals(other: Any?): Boolean {
        return other is Node && this.col == other.col && this.row == other.row
    }
}