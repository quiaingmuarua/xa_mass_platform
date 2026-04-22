package com.xa.mass.base.model;

/**
 * @deprecated Use {@link UserRef}. This compatibility wrapper keeps older
 * code paths compiling while mapping the old {@code name} accessors onto the
 * canonical {@code userId}.
 */
@Deprecated(forRemoval = false)
public class User extends UserRef {

    public String getName() {
        return getUserId();
    }

    public void setName(String name) {
        setUserId(name);
    }
}
