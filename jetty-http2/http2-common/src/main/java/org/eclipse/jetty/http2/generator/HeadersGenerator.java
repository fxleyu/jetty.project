//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http2.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class HeadersGenerator extends FrameGenerator
{
    private final HpackEncoder encoder;
    private final int maxHeaderBlockFragment;

    public HeadersGenerator(HeaderGenerator headerGenerator, HpackEncoder encoder)
    {
        this(headerGenerator, encoder, 0);
    }

    public HeadersGenerator(HeaderGenerator headerGenerator, HpackEncoder encoder, int maxHeaderBlockFragment)
    {
        super(headerGenerator);
        this.encoder = encoder;
        this.maxHeaderBlockFragment = maxHeaderBlockFragment;
    }

    @Override
    public void generate(ByteBufferPool.Lease lease, Frame frame)
    {
        HeadersFrame headersFrame = (HeadersFrame)frame;
        generateHeaders(lease, headersFrame.getStreamId(), headersFrame.getMetaData(), headersFrame.getPriority(), headersFrame.isEndStream());
    }

    public void generateHeaders(ByteBufferPool.Lease lease, int streamId, MetaData metaData, PriorityFrame priority, boolean endStream)
    {
        if (streamId < 0)
            throw new IllegalArgumentException("Invalid stream id: " + streamId);

        int flags = Flags.NONE;

        if (priority != null)
        {
            flags = Flags.PRIORITY;
            int parentStreamId = priority.getParentStreamId();
            if (parentStreamId < 0)
                throw new IllegalArgumentException("Invalid parent stream id: " + parentStreamId);
            if (parentStreamId == streamId)
                throw new IllegalArgumentException("Stream " + streamId + " cannot depend on stream " + parentStreamId);
            int weight = priority.getWeight();
            if (weight > 255)
                throw new IllegalArgumentException("Invalid weight: " + weight);
        }

        int maxFrameSize = getMaxFrameSize();
        ByteBuffer hpacked = lease.acquire(maxFrameSize, false);
        BufferUtil.clearToFill(hpacked);
        encoder.encode(hpacked, metaData);
        int hpackedLength = hpacked.position();
        BufferUtil.flipToFlush(hpacked, 0);

        // Split into CONTINUATION frames if necessary.
        if (maxHeaderBlockFragment > 0 && hpackedLength > maxHeaderBlockFragment)
        {
            if (endStream)
                flags |= Flags.END_STREAM;

            int length = maxHeaderBlockFragment;
            if (priority != null)
                length += PriorityFrame.PRIORITY_LENGTH;

            ByteBuffer header = generateHeader(lease, FrameType.HEADERS, length, flags, streamId);
            BufferUtil.flipToFlush(header, 0);
            lease.append(header, true);

            generatePriority(lease, priority);

            hpacked.limit(maxHeaderBlockFragment);
            lease.append(hpacked.slice(), false);

            int position = maxHeaderBlockFragment;
            int limit = position + maxHeaderBlockFragment;
            while (limit < hpackedLength)
            {
                hpacked.position(position).limit(limit);
                header = generateHeader(lease, FrameType.CONTINUATION, maxHeaderBlockFragment, Flags.NONE, streamId);
                BufferUtil.flipToFlush(header, 0);
                lease.append(header, true);
                lease.append(hpacked.slice(), false);
                position += maxHeaderBlockFragment;
                limit += maxHeaderBlockFragment;
            }

            hpacked.position(position).limit(hpackedLength);
            header = generateHeader(lease, FrameType.CONTINUATION, hpacked.remaining(), Flags.END_HEADERS, streamId);
            BufferUtil.flipToFlush(header, 0);
            lease.append(header, true);
            lease.append(hpacked, true);
        }
        else
        {
            flags |= Flags.END_HEADERS;
            if (endStream)
                flags |= Flags.END_STREAM;

            int length = hpackedLength;
            if (priority != null)
                length += PriorityFrame.PRIORITY_LENGTH;

            ByteBuffer header = generateHeader(lease, FrameType.HEADERS, length, flags, streamId);
            BufferUtil.flipToFlush(header, 0);
            lease.append(header, true);

            generatePriority(lease, priority);

            lease.append(hpacked, true);
        }
    }

    private void generatePriority(ByteBufferPool.Lease lease, PriorityFrame priority)
    {
        if (priority != null)
        {
            int parentStreamId = priority.getParentStreamId() & 0x7F_FF_FF_FF;
            if (priority.isExclusive())
                parentStreamId |= 0x80_00_00_00;
            ByteBuffer buffer = lease.acquire(PriorityFrame.PRIORITY_LENGTH, true);
            buffer.putInt(parentStreamId);
            buffer.put((byte)(priority.getWeight() & 0xFF));
            BufferUtil.flipToFlush(buffer, 0);
            lease.append(buffer, true);
        }
    }
}
