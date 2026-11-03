export type Claim = {
  text: string;
  evidence: { paragraphId: string; quote: string }[];
};
export type ResultBody = {
  data: {
    schemaVersion?: string;
    review?: Review;
    title: string;
    summary: Claim;
    keyPoints: Claim[];
    constraints: Claim[];
    unknowns: string[];
  };
  paragraphs: { paragraphId: string; text: string }[];
};
export type Review = {
  findings: { kind: "GAP" | "AMBIGUITY" | "CONFLICT"; text: string; question: string; evidence: Claim["evidence"] }[];
  acceptanceCriteria: { title: string; given: string; when: string; then: string; evidence: Claim["evidence"]; assumptions: string[] }[];
};
