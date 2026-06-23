package com.xa.mass.transport.runtime.embedded;

import com.google.gson.JsonObject;
import com.xa.mass.transport.runtime.frame.TransportJsonFrameParser;

import java.util.Objects;

/**
 * Reads public worker-channel ACTION_REPLY frames into adapter result facts.
 */
public final class WorkerChannelActionReplyResultFrameReader implements AdapterResultFrameReader<JsonObject> {

    private final TransportJsonFrameParser parser;
    private final WorkerChannelActionReplyReader actionReplyReader;

    public WorkerChannelActionReplyResultFrameReader(TransportJsonFrameParser parser) {
        this(parser, new WorkerChannelActionReplyReader());
    }

    WorkerChannelActionReplyResultFrameReader(TransportJsonFrameParser parser,
                                              WorkerChannelActionReplyReader actionReplyReader) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.actionReplyReader = Objects.requireNonNull(actionReplyReader, "actionReplyReader");
    }

    @Override
    public boolean isResultFrame(JsonObject frame) {
        return frame != null && actionReplyReader.isActionReplyFrame(parser.toJson(frame));
    }

    @Override
    public AdapterResultFrame read(JsonObject resultFrame) {
        WorkerChannelActionReplyFrame actionReply = actionReplyReader.read(parser.toJson(resultFrame));
        return new AdapterResultFrame(
                actionReply.replyRef(),
                actionReply.body(),
                actionReply.frameId(),
                actionReply.frameId()
        );
    }
}
