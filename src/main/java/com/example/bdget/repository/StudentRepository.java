package com.example.bdget.repository;

import com.example.bdget.model.Student;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA genera automáticamente todas las operaciones
 * CRUD (findAll, findById, save, deleteById, existsById) sin
 * necesidad de escribir SQL.
 */
public interface StudentRepository extends JpaRepository<Student, Long> {
}
