package com.codepilot1c.core.edt.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of metadata index scan.
 */
public class MetadataIndexResult {

    private final String projectName;
    private final String engine;
    private final String scope;
    private final String language;
    private final int total;
    private final int offset;
    private final int returned;
    private final boolean hasMore;
    private final int nextOffset;
    private final List<Item> items;

    /**
     * Backwards-compatible constructor for callers created before offset pagination.
     */
    public MetadataIndexResult(
            String projectName,
            String engine,
            String scope,
            String language,
            int total,
            int returned,
            boolean hasMore,
            List<Item> items) {
        this(projectName, engine, scope, language, total, 0, returned, hasMore, returned, items);
    }

    public MetadataIndexResult(
            String projectName,
            String engine,
            String scope,
            String language,
            int total,
            int offset,
            int returned,
            boolean hasMore,
            int nextOffset,
            List<Item> items) {
        this.projectName = projectName;
        this.engine = engine;
        this.scope = scope;
        this.language = language;
        this.total = total;
        this.offset = offset;
        this.returned = returned;
        this.hasMore = hasMore;
        this.nextOffset = nextOffset;
        this.items = new ArrayList<>(items != null ? items : List.of());
    }

    public String getProjectName() {
        return projectName;
    }

    public String getEngine() {
        return engine;
    }

    public String getScope() {
        return scope;
    }

    public String getLanguage() {
        return language;
    }

    public int getTotal() {
        return total;
    }

    public int getOffset() {
        return offset;
    }

    public int getReturned() {
        return returned;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public int getNextOffset() {
        return nextOffset;
    }

    public List<Item> getItems() {
        return Collections.unmodifiableList(items);
    }

    public static class Item {
        private final String fqn;
        private final String name;
        private final String synonym;
        private final String comment;
        private final String kind;
        private final String kindCode;
        private final String sourceCollection;
        private final String scopeCode;
        private final boolean hasObjectModule;
        private final boolean hasManagerModule;

        public Item(
                String fqn,
                String name,
                String synonym,
                String comment,
                String kind,
                String kindCode,
                String sourceCollection,
                String scopeCode,
                boolean hasObjectModule,
                boolean hasManagerModule) {
            this.fqn = fqn;
            this.name = name;
            this.synonym = synonym;
            this.comment = comment;
            this.kind = kind;
            this.kindCode = kindCode;
            this.sourceCollection = sourceCollection;
            this.scopeCode = scopeCode;
            this.hasObjectModule = hasObjectModule;
            this.hasManagerModule = hasManagerModule;
        }

        public String getFqn() {
            return fqn;
        }

        public String getName() {
            return name;
        }

        public String getSynonym() {
            return synonym;
        }

        public String getComment() {
            return comment;
        }

        public String getKind() {
            return kind;
        }

        public String getKindCode() {
            return kindCode;
        }

        public String getSourceCollection() {
            return sourceCollection;
        }

        public String getScopeCode() {
            return scopeCode;
        }

        public boolean isHasObjectModule() {
            return hasObjectModule;
        }

        public boolean isHasManagerModule() {
            return hasManagerModule;
        }
    }
}
