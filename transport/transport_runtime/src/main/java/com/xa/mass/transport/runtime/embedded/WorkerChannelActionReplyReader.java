package com.xa.mass.transport.runtime.embedded;

import com.google.gson.JsonObject;
import com.xa.mass.contract.worker.WorkerChannelFrame;
import com.xa.mass.contract.worker.WorkerChannelFrameJsonCodec;
import com.xa.mass.transport.runtime.frame.TransportJsonFrameParser;

import java.util.Objects;

/**
 * Embedded adapter reader for worker-channel ACTION_REPLY payloads.
 */
public final class WorkerChannelActionReplyReader {

    private final WorkerChannelFrameJsonCodec frameJsonCodec;
    private final TransportJsonFrameParser bodyParser;

    public WorkerChannelActionReplyReader() {
        this(new WorkerChannelFrameJsonCodec(), new TransportJsonFrameParser());
    }

    WorkerChannelActionReplyReader(WorkerChannelFrameJsonCodec frameJsonCodec,
                                   TransportJsonFrameParser bodyParser) {
        this.frameJsonCodec = Objects.requireNonNull(frameJsonCodec, "frameJsonCodec");
        this.bodyParser = Objects.requireNonNull(bodyParser, "bodyParser");
    }

    public boolean isActionReplyFrame(String frameJson) {
        WorkerChannelFrame frame = decodeOrNull(frameJson);
        return frame != null && WorkerChannelFrame.ACTION_REPLY.equals(frame.kind());
    }

    public WorkerChannelActionReplyFrame read(String frameJson) {
        WorkerChannelFrame frame = frameJsonCodec.decode(frameJson);
        if (!WorkerChannelFrame.ACTION_REPLY.equals(frame.kind())) {
            throw new IllegalArgumentException("worker channel frame kind must be ACTION_REPLY");
        }
        return new WorkerChannelActionReplyFrame(
                frame.frameId(),
                extractReplyRefFromBody(frame.body()),
                frame.body()
        );
    }

    public String replyRef(String frameJson) {
        return read(frameJson).replyRef();
    }

    private String extractReplyRefFromBody(String body) {
        JsonObject root = bodyParser.parseObject(body);
        if (root == null) {
            throw new IllegalArgumentException("ACTION_REPLY body must be a JSON object");
        }
        String replyRef = bodyParser.readString(root, "replyRef");
        if (replyRef == null) {
            throw new IllegalArgumentException("replyRef is required");
        }
        return replyRef;
    }

    private WorkerChannelFrame decodeOrNull(String frameJson) {
        try {
            return frameJsonCodec.decode(frameJson);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
