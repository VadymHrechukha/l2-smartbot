package net.sf.l2j.gameserver.smartbot.model;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class SmartBotKnowledge
{
    public static final SmartBotKnowledge INSTANCE = new SmartBotKnowledge();

    private static final File KNOWLEDGE_DIR = new File("config/smartbot/knowledge");

    private final List<KnowledgeFact> facts = new ArrayList<>();

    private SmartBotKnowledge()
    {
    }

    public void load()
    {
        facts.clear();

        if (!KNOWLEDGE_DIR.exists() || !KNOWLEDGE_DIR.isDirectory())
        {
            System.out.println("[SmartBotKnowledge] Knowledge folder not found: " + KNOWLEDGE_DIR.getPath());
            return;
        }

        final File[] files = KNOWLEDGE_DIR.listFiles();
        if (files == null)
        {
            return;
        }

        for (File file : files)
        {
            if (file == null || !file.isFile())
            {
                continue;
            }

            if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".txt"))
            {
                continue;
            }

            loadFile(file);
        }

        System.out.println("[SmartBotKnowledge] Loaded " + facts.size() + " knowledge facts.");
    }

    private void loadFile(File file)
    {
        try (BufferedReader reader = new BufferedReader(new FileReader(file)))
        {
            String line;

            while ((line = reader.readLine()) != null)
            {
                line = line.trim();

                if (line.isEmpty() || line.startsWith("#"))
                {
                    continue;
                }

                final KnowledgeFact fact = parseFact(line, file.getName());
                if (fact != null)
                {
                    facts.add(fact);
                }
            }
        }
        catch (Exception e)
        {
            System.out.println("[SmartBotKnowledge] Failed loading " + file.getName());
            e.printStackTrace();
        }
    }

    private KnowledgeFact parseFact(String line, String source)
    {
        String tags = "";
        String text = line;

        if (line.startsWith("["))
        {
            final int end = line.indexOf(']');
            if (end > 0)
            {
                tags = line.substring(1, end).trim();
                text = line.substring(end + 1).trim();
            }
        }

        if (text.isEmpty())
        {
            return null;
        }

        return new KnowledgeFact(tags, text, source);
    }

    public String findRelevant(String message, int limit)
    {
        if (message == null || message.trim().isEmpty() || facts.isEmpty())
        {
            return "";
        }

        final String query = normalize(message);
        final List<ScoredFact> scored = new ArrayList<>();

        for (KnowledgeFact fact : facts)
        {
            final int score = score(query, fact);
            if (score > 0)
            {
                scored.add(new ScoredFact(fact, score));
            }
        }

        scored.sort(Comparator.comparingInt(ScoredFact::getScore).reversed());

        final StringBuilder sb = new StringBuilder();
        int count = 0;

        for (ScoredFact scoredFact : scored)
        {
            if (count >= limit)
            {
                break;
            }

            if (sb.length() > 0)
            {
                sb.append('\n');
            }

            sb.append("- ").append(scoredFact.getFact().getText());
            count++;
        }

        return sb.toString();
    }

    private int score(String query, KnowledgeFact fact)
    {
        int score = 0;

        final String normalizedTags = normalize(fact.getTags());
        final String normalizedText = normalize(fact.getText());

        final String[] words = query.split("\\s+");

        for (String word : words)
        {
            if (word.length() < 3)
            {
                continue;
            }

            if (normalizedTags.contains(word))
            {
                score += 5;
            }

            if (normalizedText.contains(word))
            {
                score += 2;
            }
        }

        // Intent boosts.
        if (containsAny(query, "where", "location", "farm", "level", "xp", "hunt", "zone"))
        {
            if (containsAny(normalizedTags, "location", "level", "farm"))
            {
                score += 6;
            }
        }

        if (containsAny(query, "buff", "buffs", "song", "songs", "dance", "dances"))
        {
            if (containsAny(normalizedTags, "buff", "song", "dance"))
            {
                score += 6;
            }
        }

        if (containsAny(query, "assist", "attack", "fight", "kill", "mob"))
        {
            if (containsAny(normalizedTags, "assist", "attack", "combat", "party", "role"))
            {
                score += 5;
            }
        }

        if (containsAny(query, "heal", "healer", "hp", "res", "ress", "resurrect", "revive"))
        {
            if (containsAny(normalizedTags, "healer", "heal", "resurrect", "revive"))
            {
                score += 7;
            }
        }

        if (containsAny(query, "mage", "nuke", "spell", "magic"))
        {
            if (containsAny(normalizedTags, "mage", "magic", "nuker"))
            {
                score += 5;
            }
        }

        if (containsAny(query, "fighter", "archer", "dagger", "tank", "physical"))
        {
            if (containsAny(normalizedTags, "fighter", "archer", "dagger", "tank", "physical"))
            {
                score += 5;
            }
        }

        return score;
    }

    private boolean containsAny(String text, String... values)
    {
        if (text == null || text.isEmpty())
        {
            return false;
        }

        for (String value : values)
        {
            if (text.contains(value))
            {
                return true;
            }
        }

        return false;
    }

    private String normalize(String text)
    {
        if (text == null)
        {
            return "";
        }

        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static class KnowledgeFact
    {
        private final String tags;
        private final String text;
        private final String source;

        private KnowledgeFact(String tags, String text, String source)
        {
            this.tags = tags;
            this.text = text;
            this.source = source;
        }

        public String getTags()
        {
            return tags;
        }

        public String getText()
        {
            return text;
        }

        @SuppressWarnings("unused")
        public String getSource()
        {
            return source;
        }
    }

    private static class ScoredFact
    {
        private final KnowledgeFact fact;
        private final int score;

        private ScoredFact(KnowledgeFact fact, int score)
        {
            this.fact = fact;
            this.score = score;
        }

        public KnowledgeFact getFact()
        {
            return fact;
        }

        public int getScore()
        {
            return score;
        }
    }
}