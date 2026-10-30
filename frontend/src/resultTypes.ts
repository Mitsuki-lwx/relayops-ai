export type Claim = {
  text: string;
  evidence: { paragraphId: string; quote: string }[];
};
export type ResultBody = {
  data: {
    title: string;
    summary: Claim;
    keyPoints: Claim[];
    constraints: Claim[];
    unknowns: string[];
  };
  paragraphs: { paragraphId: string; text: string }[];
};
