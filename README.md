# Overview
This project decompile springboot maven jar, try to reconstruct the maven project structure. 
- use vineflower to do the decompilation
- query search.maven.org and repo1.maven.org about whether a dependency is private or public, and the GAV of public dependency.
- Also decompile private dependency and put source together