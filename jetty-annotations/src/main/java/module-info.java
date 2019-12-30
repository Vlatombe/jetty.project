//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

import javax.servlet.ServletContainerInitializer;

import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.webapp.Configuration;

module org.eclipse.jetty.annotations
{
    exports org.eclipse.jetty.annotations;

    requires java.annotation;
    requires java.naming;
    requires transitive org.eclipse.jetty.plus;
    requires transitive org.objectweb.asm;

    uses ServletContainerInitializer;

    provides Configuration with AnnotationConfiguration;
}