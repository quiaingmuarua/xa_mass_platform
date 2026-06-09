package com.xa.mass.server;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
        ServerMemoryLocalProfileContextTest.class,
        ServerDurableLocalProfileContextTest.class
})
class ServerStartupProfileSuite {
}
