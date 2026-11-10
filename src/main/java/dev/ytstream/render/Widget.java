package dev.ytstream.render;

import dev.ytstream.model.ServerModel;

/** 配信画面の部品。新機能はこのインターフェースを実装して FrameRenderer に登録する。 */
interface Widget {
    void render(RenderContext c);

    /** アニメーション中(毎フレーム再描画が必要)か。静止時はフレーム再利用で負荷を抑える。 */
    boolean isAnimating(long now, ServerModel model);
}
