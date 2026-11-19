/*
 *  Copyright 2026 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/**
 * What an answer rests on, written by the server from catalog reads made as the caller. It
 * is the `structuredContent` of `finish_answer`; the Markdown block is rendered from it.
 */
export interface AnswerCitation {
    concepts?: Concept[];
    execution: Execution;
    /**
     * The agent's one-line answer, quoted as the agent's.
     */
    headline: string;
    /**
     * True when the cited items were checked against a plan from start_answer.
     */
    planChecked?: boolean;
    question:     string;
    /**
     * Whether the harness shows SQL in the block. The block shows it regardless when the SQL
     * was not run.
     */
    showSql?:      boolean;
    sql?:          string;
    tables?:       Table[];
    timeWindow?:   TimeWindow;
    traceUrl?:     string;
    trustChecked?: TrustChecked;
    /**
     * Written by the server only.
     */
    warnings?: string[];
}

/**
 * A glossary term or metric the answer used, as the caller sees it.
 */
export interface Concept {
    certified?:   boolean;
    displayName?: string;
    entityType?:  ConceptType;
    /**
     * Fully qualified name. Absent when the caller cannot view it.
     */
    fqn?:    string;
    owners?: string[];
    /**
     * The words of the question it was bound to.
     */
    phrase: string;
    /**
     * Entity status, for example Approved or Draft.
     */
    status?:     string;
    unresolved?: Unresolved;
    /**
     * Link to the concept in OpenMetadata.
     */
    url?: string;
    /**
     * Entity version cited.
     */
    version?: number;
}

/**
 * Kind of concept an answer binds a phrase to.
 */
export enum ConceptType {
    GlossaryTerm = "glossaryTerm",
    Metric = "metric",
}

/**
 * Why a name was not resolved. notFound: nothing has that name. notVisible: the caller may
 * not view it, and no fact about it is returned.
 */
export enum Unresolved {
    NotFound = "notFound",
    NotVisible = "notVisible",
}

export interface Execution {
    connector?:  string;
    durationMs?: number;
    executor:    Executor;
    reason?:     string;
    rowCount?:   number;
    /**
     * True when the caller's agent ran the SQL and OpenMetadata did not observe it.
     */
    selfReported?: boolean;
}

/**
 * Who ran the SQL. sqlStudio is set only by Collate.
 */
export enum Executor {
    HostConnector = "hostConnector",
    None = "none",
    SQLStudio = "sqlStudio",
}

/**
 * A table the answer read, with the trust signals OpenMetadata already holds for it.
 */
export interface Table {
    certified?:    boolean;
    deprecated?:   boolean;
    failingTests?: FailingTest[];
    /**
     * Fully qualified name. Absent when the caller cannot view it.
     */
    fqn?:           string;
    freshness?:     Freshness;
    openIncidents?: OpenIncident[];
    owners?:        string[];
    /**
     * Fully qualified name of the table that replaces a deprecated one, when the catalog
     * records it.
     */
    replacedBy?: string;
    /**
     * Tier tag, for example Tier1.
     */
    tier?:       string;
    unresolved?: Unresolved;
    url?:        string;
}

export interface FailingTest {
    name: string;
    url?: string;
}

export interface Freshness {
    /**
     * When the freshness was last checked.
     */
    at?:    number;
    source: FreshnessSource;
    /**
     * True when it is older than the harness's staleAfter.
     */
    stale?: boolean;
}

/**
 * Where a table's freshness time comes from: its latest freshness test, else its latest
 * profile, else unknown.
 */
export enum FreshnessSource {
    Profile = "profile",
    Test = "test",
    Unknown = "unknown",
}

export interface OpenIncident {
    /**
     * Incident state id.
     */
    id:        string;
    severity?: string;
    status?:   string;
    /**
     * Test case the incident was opened for.
     */
    testCase?: string;
    url?:      string;
}

/**
 * The window the answer covers.
 */
export interface TimeWindow {
    /**
     * Last day, yyyy-MM-dd.
     */
    end?: string;
    /**
     * MM-DD.
     */
    fiscalYearStart?: string;
    /**
     * Name of the resolved period, for example October 2026 or fiscal Q3 FY2026.
     */
    label?:     string;
    phrase?:    string;
    resolvedBy: ResolvedBy;
    /**
     * First day, yyyy-MM-dd.
     */
    start?:    string;
    timeZone?: string;
}

/**
 * Who decided the dates of the time window.
 */
export enum ResolvedBy {
    Agent = "agent",
    Server = "server",
}

/**
 * Which trust signals the harness reports, so that an empty list means none were found
 * rather than none were looked for.
 */
export interface TrustChecked {
    failingTests?:  boolean;
    openIncidents?: boolean;
}
