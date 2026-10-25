import { describe, expect, it } from 'vitest';
import type { ClassifierResource, ModelResource, RelationshipResource } from '../api/types';
import { associationClassCandidates, associationClassLink, isAssociationKind } from './associationClass';

const classifier = (id: string, name: string, kind: string): ClassifierResource => ({
  id,
  name,
  kind,
  isAbstract: false,
  namespaceId: null,
  attributes: [],
  operations: [],
  constraints: [],
} as unknown as ClassifierResource);

const end = (id: string, classifierId: string, ordinal: number) => ({
  id,
  classifierId,
  role: null,
  multiplicity: 'EXACTLY_ONE',
  navigability: 'UNSPECIFIED',
  aggregationKind: 'NONE',
  ordinal,
});

const relationship = (id: string, source: string, target: string, associationClassId: string | null = null) => ({
  id,
  kind: 'ASSOCIATION',
  name: null,
  associationClassId,
  ends: [end(`${id}-a`, source, 1), end(`${id}-b`, target, 2)],
  constraints: [],
} as unknown as RelationshipResource);

const modelWith = (classifiers: ClassifierResource[], relationships: RelationshipResource[]) => ({
  id: 'model',
  projectId: 'project',
  revision: 1,
  versionId: 'version',
  views: [],
  classifiers,
  relationships,
} as unknown as ModelResource);

describe('isAssociationKind', () => {
  it('is true for association, aggregation and composition and false for the rest', () => {
    expect(['ASSOCIATION', 'AGGREGATION', 'COMPOSITION'].map(isAssociationKind)).toEqual([true, true, true]);
    expect(['GENERALIZATION', 'INTERFACE_REALIZATION', 'DEPENDENCY'].map(isAssociationKind)).toEqual([false, false, false]);
  });
});

describe('associationClassCandidates', () => {
  const student = classifier('student', 'Student', 'CLASS');
  const course = classifier('course', 'Course', 'CLASS');
  const enrolment = classifier('enrolment', 'Enrolment', 'CLASS');
  const grade = classifier('grade', 'Grade', 'ABSTRACT_CLASS');
  const contract = classifier('contract', 'Contract', 'INTERFACE');
  const colour = classifier('colour', 'Colour', 'ENUMERATION');
  const school = classifier('school', 'School', 'PACKAGE');
  const studies = relationship('studies', 'student', 'course');

  it('offers the classes that are not an end, in the order of the model', () => {
    const model = modelWith([student, course, enrolment, grade, contract, colour, school], [studies]);

    expect(associationClassCandidates(model, studies).map((candidate) => candidate.name)).toEqual(['Enrolment', 'Grade']);
  });

  it('leaves out a class that already is the association class of another relationship', () => {
    const teaches = relationship('teaches', 'student', 'course', 'enrolment');
    const model = modelWith([student, course, enrolment, grade], [studies, teaches]);

    expect(associationClassCandidates(model, studies).map((candidate) => candidate.name)).toEqual(['Grade']);
  });

  it('keeps the class a relationship already has, so its own choice stays selectable', () => {
    const linked = relationship('studies', 'student', 'course', 'enrolment');
    const model = modelWith([student, course, enrolment, grade], [linked]);

    expect(associationClassCandidates(model, linked).map((candidate) => candidate.name)).toEqual(['Enrolment', 'Grade']);
  });
});

describe('associationClassLink', () => {
  const box = { x: 100, y: 100, width: 200, height: 80 };

  it('runs to the nearest point of the side facing the association', () => {
    expect(associationClassLink({ x: 20, y: 140 }, box)).toEqual({ from: { x: 20, y: 140 }, to: { x: 100, y: 140 } });
    expect(associationClassLink({ x: 350, y: 140 }, box)).toEqual({ from: { x: 350, y: 140 }, to: { x: 300, y: 140 } });
    expect(associationClassLink({ x: 150, y: 20 }, box)).toEqual({ from: { x: 150, y: 20 }, to: { x: 150, y: 100 } });
    expect(associationClassLink({ x: 150, y: 260 }, box)).toEqual({ from: { x: 150, y: 260 }, to: { x: 150, y: 180 } });
  });

  it('aims at the closest corner when the association is diagonal to the class', () => {
    expect(associationClassLink({ x: 40, y: 40 }, box)?.to).toEqual({ x: 100, y: 100 });
    expect(associationClassLink({ x: 340, y: 220 }, box)?.to).toEqual({ x: 300, y: 180 });
  });

  it('draws nothing when the association sits on the class itself', () => {
    expect(associationClassLink({ x: 150, y: 140 }, box)).toBeNull();
  });
});
