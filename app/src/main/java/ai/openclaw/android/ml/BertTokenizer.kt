package ai.openclaw.android.ml

import java.io.InputStream

class BertTokenizer(vocabStream: InputStream) {

    private val vocab = mutableMapOf<String, Int>()
    private val invVocab = mutableMapOf<Int, String>()

    init {
        vocabStream.bufferedReader().use { reader ->
            var index = 0
            reader.forEachLine { line ->
                vocab[line.trim()] = index
                invVocab[index] = line.trim()
                index++
            }
        }
    }

    fun tokenize(text: String, maxLength: Int): Pair<IntArray, IntArray> {
        // 简化版 tokenizer
        // 实际实现需要处理 WordPiece 分词
        val tokens = mutableListOf("[CLS]")
        val words = text.lowercase().split(Regex("\\s+"))

        words.take(maxLength - 2).forEach { word ->
            tokens.add(word)
        }
        tokens.add("[SEP]")

        val inputIds = IntArray(maxLength) { 0 }
        val attentionMask = IntArray(maxLength) { 0 }

        tokens.forEachIndexed { i, token ->
            if (i < maxLength) {
                inputIds[i] = vocab[token] ?: vocab["[UNK]"] ?: 0
                attentionMask[i] = 1
            }
        }

        return Pair(inputIds, attentionMask)
    }
}