package net.sf.l2j.gameserver.smartbot.model;

import java.util.ArrayDeque;
import java.util.Deque;

public class BotMemory
{
    private final Deque<String> lines = new ArrayDeque<>();
    private final int maxLines;

    public BotMemory(int maxLines)
    {
        this.maxLines = maxLines;
    }

    public void add(String line)
    {
        if (line == null)
        {
            return;
        }

        final String trimmed = line.trim();
        if (trimmed.isEmpty())
        {
            return;
        }

        lines.addLast(trimmed);

        while (lines.size() > maxLines)
        {
            lines.removeFirst();
        }
    }

    public String render()
    {
        final StringBuilder sb = new StringBuilder();

        for (String line : lines)
        {
            if (sb.length() > 0)
            {
                sb.append('\n');
            }
            sb.append(line);
        }

        return sb.toString();
    }

    public void clear()
    {
        lines.clear();
    }

    public boolean isEmpty()
    {
        return lines.isEmpty();
    }
}