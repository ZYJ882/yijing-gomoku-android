#include <jni.h>
#include <array>
#include <vector>

namespace {
constexpr int kBoardSize = 15;
constexpr int kBoardCells = kBoardSize * kBoardSize;
constexpr int kEmpty = 0;
constexpr std::array<std::array<int, 2>, 4> kDirections{{
    {{1, 0}}, {{0, 1}}, {{1, 1}}, {{1, -1}},
}};

inline bool inside(int row, int col) {
    return row >= 0 && row < kBoardSize && col >= 0 && col < kBoardSize;
}

inline int indexOf(int row, int col) {
    return row * kBoardSize + col;
}

bool isFiveAt(const std::array<jint, kBoardCells>& board, int row, int col, jint player) {
    for (const auto& direction : kDirections) {
        const int dr = direction[0];
        const int dc = direction[1];
        int count = 1;

        int r = row + dr;
        int c = col + dc;
        while (inside(r, c) && board[indexOf(r, c)] == player) {
            ++count;
            r += dr;
            c += dc;
        }

        r = row - dr;
        c = col - dc;
        while (inside(r, c) && board[indexOf(r, c)] == player) {
            ++count;
            r -= dr;
            c -= dc;
        }

        if (count >= 5) return true;
    }
    return false;
}
}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_gomoku_android_ai_NativeTacticalScanner_nativeWinningMoveIndices(
    JNIEnv* env,
    jobject /* receiver */,
    jintArray boardArray,
    jint player
) {
    if (boardArray == nullptr || env->GetArrayLength(boardArray) != kBoardCells ||
        (player != 1 && player != 2)) {
        return env->NewIntArray(0);
    }

    std::array<jint, kBoardCells> board{};
    env->GetIntArrayRegion(boardArray, 0, kBoardCells, board.data());

    std::vector<jint> wins;
    wins.reserve(4);
    for (int row = 0; row < kBoardSize; ++row) {
        for (int col = 0; col < kBoardSize; ++col) {
            const int index = indexOf(row, col);
            if (board[index] != kEmpty) continue;
            board[index] = player;
            if (isFiveAt(board, row, col, player)) wins.push_back(index);
            board[index] = kEmpty;
        }
    }

    jintArray result = env->NewIntArray(static_cast<jsize>(wins.size()));
    if (result != nullptr && !wins.empty()) {
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(wins.size()), wins.data());
    }
    return result;
}
