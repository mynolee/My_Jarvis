# Synthetic project metadata contracts

These fixtures contain invented public test data only. They validate wire shapes, not authentication, authorization, encryption, transactions, or runtime behavior.

The original v4 API sections 17.1 and 17.3 motivate create/list, snake_case, RFC3339, UUID identifiers, owner and CSRF checks, idempotency, and paging default 20 / maximum 100. The narrower response shape, name and description limits, control-character choices, immutable active/version fields, 1024-character opaque cursor limit, descending created_at/id ordering, get route, and receipt route are implementation decisions for this minimal slice.

Schemas describe response data inside the existing data/request_id envelope. POST /api/v1/projects returns a project-record. GET /api/v1/projects returns project-list. GET /api/v1/projects/{id} returns a project-record. GET /api/v1/project-creations/{key} returns project-create-receipt with HTTP 200 for either observed state. IDs and Idempotency-Key are canonical lowercase UUIDs. The headers schema models only the required logical header subset.

Create requires exactly name and description. Description is non-null (empty allowed), encrypted by runtime; plaintext fixtures are only response/request data. Same key and body returns the prior result; changed body is a conflict. Receipt lookup is read-only and owner-scoped. NOT_OBSERVED does not prove that a create failed or cannot later commit. Never automatically replay a create or generate a replacement key after an uncertain response.

Direct user forms must be authenticated and CSRF protected. These schemas cannot authenticate a caller and do not activate an AI tool, R2 grant, memory context, update or delete operation. Runtime tests must prove these boundaries.

All project text rejects lone UTF-16 surrogates at strict JSON parsing and schema validation. Valid astral code points remain allowed and count as one code point. unicode-parse-cases.json keeps malformed inputs as raw JSON strings so the fixture itself remains valid Unicode.
