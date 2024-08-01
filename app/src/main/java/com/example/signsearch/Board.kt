package com.example.signsearch

import java.util.LinkedList
import kotlin.random.Random


class Board(
    val width: Int,
    val height: Int,
    private val transferInit: String? = "RSTCS DEIAE GNLRP EATES MSSID"
) {
    var grid: MutableList<MutableList<Node>>

    fun search(word: String): List<Node> {
        if (word.length > height * width) return LinkedList()
        return _internalSearchHelper(word.uppercase(), LinkedList())
    }

    fun getCharGrid(): List<List<Char>> {
        return this.grid.map { it.map { node -> node.char } }
    }

    private fun _internalSearchHelper(word: String, currPath: List<Node>): List<Node> {
        if (word.isEmpty()) return currPath
        val result =
            grid.flatMap { it.asIterable() }.filter { it.char == word[0] && it !in currPath }.map {
                _internalSearchHelperRecursive(
                    it,
                    word.substring(1),
                    currPath.toMutableList().also { list -> list.add(it) })
            }.filter { it.isNotEmpty() }
        return if (result.isNotEmpty()) result[0] else LinkedList()
    }

    private fun _internalSearchHelperRecursive(
        currNode: Node, word: String, currPath: List<Node>
    ): List<Node> {
        if (word.isEmpty()) return currPath
        val result = currNode.getNeighbours(this).filter { it.char == word[0] && it !in currPath }
            .map { neighbour ->
                _internalSearchHelperRecursive(
                    neighbour,
                    word.substring(1),
                    currPath.toMutableList().also { it.add(neighbour) })
            }.filter { it.isNotEmpty() }
        return if (result.isNotEmpty()) result[0] else LinkedList()
    }

    fun lengthScore(wordList: List<String>, ideal: Boolean = false): Int {
        return wordList.sumOf {
//            (mutableListOf(0, 0, 0, 1, 1, 2, 3, 5).also { it.addAll(Array(99) {11}) })[if (!ideal) search(it).size else it.length]
            (mutableListOf(0, 1, 1, 2, 3, 5,  7, 9).also { it.addAll(Array(99) {11}) })[if (!ideal) search(it.filter{it.isLetter()}).size else it.length]
        }
    }

    fun foundList(wordList: List<String>): List<String> {
        return wordList.filter { search(it.filter { it.isLetter() }).isNotEmpty() }
    }

    private var _rollbackGrid: MutableList<MutableList<Node>> = mutableListOf()
//    private val rand = Random(System.currentTimeMillis())
    private fun transaction() {
        _rollbackGrid = grid.map { it.map { Node(it.char, it.row, it.col) }.toMutableList() }.toMutableList()
    }
    private fun rollback() {
        this.grid = _rollbackGrid
    }
    private fun mutate() {
        val row = Random.nextInt(0, height)
        val col = Random.nextInt(width)
        grid[row][col] = Node('A' + Random.nextInt(0, 25), row, col)
    }

    fun train(wordList: List<String>, epochs: Int = 1000): Int {
        var minScore = lengthScore(wordList)
        var score = minScore
        for (i in 0..<epochs) {
            transaction()
            mutate()
            // TODO: Use RL techniques to allow for a better scoring by looking ahead with more and more changes/ steps
            score = lengthScore(wordList)
            if (score < minScore) {
                rollback()
                score = minScore
            }
            else if (score == minScore) {
                if (Random.nextBoolean()) {
                    rollback()
                    score = minScore
                } else minScore = score
            }
            else minScore = score
        }
        return score
    }

    fun print() {
        this.grid.forEach {
            it.forEach {
                print(it.char)
            }
            println()
        }
    }

    override fun toString(): String {
        return this.grid.map { it.map {it.char}.joinToString("") }.joinToString(" ")
    }

    companion object {
        fun pathToString(path: List<Node>): String {
            return path.map { it.char }.joinToString("")
        }

        private val cumulativeFrequencies = arrayOf(
            0.08167, 0.01492, 0.02782, 0.04253, 0.12703, 0.02228,
            0.02015, 0.06094, 0.06966, 0.00153, 0.00772, 0.04025,
            0.02406, 0.06749, 0.07507, 0.01929, 0.00095, 0.05987,
            0.06327, 0.09056, 0.02758, 0.00978, 0.02360, 0.00150,
            0.01974, 0.00074
        ).scan(0.0) { total, value -> total + value }.drop(0)

        private val letterPoints =
            arrayOf(1, 3, 3, 2, 1, 4, 2, 4, 1, 8, 5, 1, 3, 1, 1, 3, 10, 1, 1, 1, 1, 4, 4, 8, 4, 10)

        fun pathPoints(path: List<Node>): Int {
            return path.sumOf { letterPoints[it.char - 'A'] }
        }

        fun lenPoints(path: List<Node>): Int {
            return path.sumOf { 1L }.toInt()
        }

        private fun correspondingIdx(target: Double): Int {
            for (i in cumulativeFrequencies.indices) if (target <= cumulativeFrequencies[i]) return i
            return cumulativeFrequencies.size
        }

        fun genBoard(width: Int, height: Int, wordList: List<String>, patience: Int = 30, fillThresh: Double = 0.5, sublistSize: Int = 8): Board {
            var subList = wordList.map{it.uppercase()}.toMutableList().also { it.shuffle() }.also {if (it.size >= sublistSize) it.subList(0, sublistSize) }
            val initString = subList.joinToString("").uppercase().toCharArray().toMutableList()
                .filter { it.isLetter() }
                .toMutableList()
                .also { it.shuffle() }
                .also { it.addAll("ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray().asList()) }
                .subList(0, width * height).chunked(width)
                .joinToString(" ") { it.joinToString("") }
//            println("Init Gen Board: $initString $subList")
            val board = Board(width, height, initString)
//            println(board.lengthScore(subList))
            var score = board.lengthScore(subList)
//            println("Ideal Score: ${board.lengthScore(subList, true)}. Current Score: ${board.lengthScore(subList)}")
            var epoch = 0 // TODO: figure out why epochs is not needed
            var numNoGrad = 0
            var prevScore = -1
            while (numNoGrad < patience && score < board.lengthScore(subList, true) * fillThresh) {
                score = board.train(subList, 1) // TODO: epoch shouldve gone here
                if (score == prevScore) numNoGrad++
                else {
                    numNoGrad = 0
                    prevScore = score
                }
                ++epoch
                // TODO: was incrementing it here in print statement while testing - but on the phone one epoch is enough?? cuz it was commented
//                println("Score after ${++epoch}000 epochs: $score.  Ideal: ${board.lengthScore(subList, true)}. Sublist: ${subList}. Board: ${board}")
            }
//            println(board.lengthScore(subList))
//            board.print()
            return board
        }
    }

    init {
        if (transferInit == null) {
            this.grid = MutableList(height) { row ->
                MutableList(width) { col ->
                    Node(
                        'A' + correspondingIdx(Random.nextDouble()),
                        row,
                        col,
                    )
                }
            }
        } else {
            this.grid = transferInit
                .split(" ")
                .mapIndexed { row, s ->
                    s.toCharArray().mapIndexed { col, char -> Node(char, row, col) }.toMutableList()
                }.toMutableList()
        }
    }
}

fun main() {
    val x = LinkedList<String>()
    x.add("test")
    x.add("name")
    x.add("hello")
    x.add("game")
    x.add("jest")
    x.add("pest")
    x.add("lest")
    x.add("press")
    x.add("amex")
    x.add("pineapple")
    x.add("apple")
    x.add("pear")
    x.add("yellow")
    x.add("hot")
    x.add("dance")
    x.add("prance")
    x.add("weather")

//    val board = Board.genBoard(x)
//    board.print()
//    println("Score: ${board.lengthScore(x)} / ${board.lengthScore(x, true)}")
//    println("Found List\n${board.foundList(x)}")

    println(arrayOf("WHY", "LIPS", "GRASS", "EAR", "CUT", "THIRSTY", "PENCIL", "WOLF").asList().joinToString("").uppercase().toCharArray().toMutableList()
        .also { it.shuffle() }
        .also { it.addAll("ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray().asList()) }
        .subList(0, 25).chunked(5)
        .joinToString(" ") { it.joinToString("") })
}