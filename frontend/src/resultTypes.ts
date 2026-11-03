export type Evidence = { paragraphId: string; quote: string };
export type Claim = {
  text: string;
  evidence: Evidence[];
};
export type Criterion = {
  title: string;
  given: string;
  when: string;
  then: string;
  evidence: Evidence[];
  assumptions: string[];
};
export type Review = {
  findings: { kind: "GAP" | "AMBIGUITY" | "CONFLICT"; text: string; question: string; evidence: Evidence[] }[];
  acceptanceCriteria: Criterion[];
};
export type VersionChange = {
  kind: "ADDED" | "REMOVED" | "MODIFIED";
  text: string;
  before: Evidence[];
  after: Evidence[];
};
export type VersionDiff = {
  changes: VersionChange[];
  impactedConstraints: Claim[];
  impactedAcceptanceCriteria: Criterion[];
};
export type ResultBody = {
  data: {
    schemaVersion?: string;
    review?: Review;
    diff?: VersionDiff;
    title: string;
    summary: Claim;
    keyPoints: Claim[];
    constraints: Claim[];
    unknowns: string[];
  };
  paragraphs: { paragraphId: string; text: string }[];
};
