package com.byyourside.backend.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    // Backend Debt B3: orden estable -- createdAt DESC solo no alcanza si
    // dos notificaciones se crean en el mismo instante (timestamps
    // empatados), lo que podria reordenar filas entre pagina y pagina bajo
    // paginacion offset. `id DESC` como desempate secundario (UUID no tiene
    // orden temporal real, pero al menos es un criterio estable y
    // determinista para filas con createdAt identico) fija el orden total.
    @Query("""
            SELECT n FROM Notification n
            JOIN FETCH n.actor
            WHERE n.recipient.id = :recipientId
            ORDER BY n.createdAt DESC, n.id DESC
            """)
    Page<Notification> findByRecipientId(@Param("recipientId") UUID recipientId, Pageable pageable);

    // Backend Debt B3: usada por markAsRead -- el filtro por owner esta
    // DENTRO de la query (nunca findById() + chequeo aparte en el service),
    // asi que no hay forma de que un caller se olvide de validar ownership.
    @Query("""
            SELECT n FROM Notification n
            JOIN FETCH n.actor
            WHERE n.id = :id AND n.recipient.id = :recipientId
            """)
    Optional<Notification> findByIdAndRecipientId(@Param("id") UUID id, @Param("recipientId") UUID recipientId);

    long countByRecipientIdAndReadFalse(UUID recipientId);

    @Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.recipient.id = :recipientId AND n.read = false")
    void markAllAsRead(@Param("recipientId") UUID recipientId);
}
