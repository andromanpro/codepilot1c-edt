/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell.session;

import java.util.List;
import java.util.Objects;

/** Safe resume comparison data: endpoints are represented only by one-way fingerprints. */
public record SessionMismatch(SessionContext persisted, SessionContext current, List<Field> fields) {
    public enum Field { MODE, PROVIDER, MODEL, ENDPOINT }

    public SessionMismatch {
        Objects.requireNonNull(persisted, "persisted"); //$NON-NLS-1$
        Objects.requireNonNull(current, "current"); //$NON-NLS-1$
        fields = List.copyOf(fields);
    }

    public boolean present() {
        return !fields.isEmpty();
    }

    static SessionMismatch compare(SessionContext persisted, SessionContext current) {
        java.util.ArrayList<Field> fields = new java.util.ArrayList<>();
        if (!persisted.mode().equals(current.mode())) fields.add(Field.MODE);
        if (!persisted.provider().equals(current.provider())) fields.add(Field.PROVIDER);
        if (!persisted.model().equals(current.model())) fields.add(Field.MODEL);
        if (!persisted.endpointFingerprint().equals(current.endpointFingerprint())) fields.add(Field.ENDPOINT);
        return new SessionMismatch(persisted, current, fields);
    }
}
