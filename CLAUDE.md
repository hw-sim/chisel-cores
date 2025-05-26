# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

KSIM-Bench is a Scala-based SoC generator that builds upon the Berkeley RocketChip and RISC-V BOOM ecosystems. It generates synthesizable RISC-V processor configurations for architecture research and benchmarking.

## Build System & Environment

### Dependency Management
- **Build Tool**: SBT (Scala Build Tool) 
- **Environment**: Pixi package manager with conda-forge channels
- **Scala Version**: 2.13.12
- **Chisel Version**: 6.5.0 (primary), with 3.6.1 compatibility

### Key Commands
```bash
# Setup environment
pixi install

# Build and elaborate a core configuration
sbt "runMain main elaborate --dir <output_dir> --core <core_type> --ncores <num_cores>"

# Example: Generate SmallRocket single-core configuration
sbt "runMain main elaborate --dir output --core SmallRocket --ncores 1"

# Compile project
sbt compile

# Run tests
sbt test

# Generate assembly JAR
sbt assembly
```

## Core Architecture

### Supported Core Types
The system supports multiple RISC-V core configurations:

**Rocket Cores (In-order)**:
- `TinyRocket`: Minimal configuration
- `SmallRocket`: Small, efficient configuration  
- `MedRocket`: Medium performance
- `BigRocket`: High performance
- `HugeRocket`: Maximum performance

**BOOM Cores (Out-of-order)**:
- `SmallBoom`: Small out-of-order core
- `MediumBoom`: Medium out-of-order core
- `LargeBoom`: Large out-of-order core
- `MegaBoom`: Very large out-of-order core
- `GigaBoom`: Maximum out-of-order core

### Project Structure
- **Main entry point**: `src/main/scala/Main.scala` - Contains the `elaborate` function that generates processor configurations
- **Submodules**:
  - `rocket-chip/`: Contains the base RocketChip generator and all dependencies
  - `riscv-boom/`: Contains the Berkeley Out-of-Order Machine implementations
- **Dependencies**: All managed through SBT with careful version alignment between Chisel, FIRRTL, and RocketChip

### Key Dependencies Structure
```
ksim-bench (root)
├── rocketchip (core generator)
│   ├── cde (configuration library)
│   ├── diplomacy (parameter negotiation)  
│   ├── hardfloat (IEEE 754 FPU)
│   └── rocketMacros
└── boom (out-of-order cores)
```

## Development Workflow

### Configuration System
The project uses RocketChip's configuration system built on the CDE (Cake Design Environment) library. Configurations are composed by mixing traits that define parameters.

### Output Generation
- **FIRRTL**: Intermediate representation files (`.fir`)
- **Annotations**: JSON files with elaboration metadata (`.anno.json`)
- **Artifacts**: Additional generated files based on configuration

### Testing Strategy
- Use the existing test suites in the rocket-chip and riscv-boom submodules
- Configurations can be validated by successful elaboration
- Generated RTL can be verified through simulation in the rocket-chip ecosystem

## Important Notes

### Submodule Management
- Both `rocket-chip` and `riscv-boom` are Git submodules with specific commit hashes
- The repository may have patches applied to these submodules (see `patches/` directory)
- Always check submodule status when making changes

### Chisel Version Compatibility
- The project maintains compatibility with both Chisel 3.6.1 and Chisel 6.5.0
- Custom merge strategies handle FIRRTL/Chisel conflicts during assembly
- Compiler plugins are version-specific and managed automatically

### Common Pitfalls
- Ensure submodules are properly initialized before building
- Configuration composition order matters in the CDE system
- Assembly process requires custom merge strategies for Chisel/FIRRTL artifacts